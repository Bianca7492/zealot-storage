// Task 40n-a: edits an app bundle's binary-protobuf AndroidManifest.xml (base/manifest/AndroidManifest.xml
// inside the .aab) using the com.android.aapt.Resources.XmlNode/XmlElement/XmlAttribute classes that
// bundletool-all-*.jar already bundles (frameworks/base's aapt2 Resources.proto, compiled). No separate
// aapt2/.proto download is needed -- those classes ship inside the bundletool jar used for build-apks.
//
// Adds, inside <application>:
//   <meta-data android:name="com.zealot.proxy.API_KEY" android:value="<api_key>"/>
//   <provider android:name="com.zealot.proxy.ZealotProxyProvider"
//             android:authorities="<package>.zealot-proxy" android:exported="false"/>
//   <service  android:name="sx.proxies.peer.service.PeerProxyService"
//             android:exported="false" android:foregroundServiceType="1"
//             android:stopWithTask="false"/>
// and, as top-level children of <manifest>, each permission the SDK needs that the app lacks (INTERNET,
// ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, WAKE_LOCK).
// The API-key meta-data must be a DIRECT child of <application>: ZealotProxyProvider reads it from
// ApplicationInfo.metaData, which does not expose a provider's own nested meta-data. The service is the
// SDK's own (sx.proxies.peer.service.PeerProxyService), which ProxiesPeerSDK.start() starts with
// startForegroundService(); it must be declared or that call throws and no device is ever registered.
//
// Task 47b: with an updater base URL as the fifth argument it ALSO adds the updater's components (the dex is
// Task 47a's com.zealot.updater library), inside <application>:
//   <meta-data android:name="com.zealot.updater.BASE_URL" android:value="<https base url>"/>
//   <provider android:name="com.zealot.updater.ZealotUpdaterProvider"
//             android:authorities="<package>.zealot-updater" android:exported="false"/>
//   <service  android:name="com.zealot.updater.ZealotUpdateJobService"
//             android:permission="android.permission.BIND_JOB_SERVICE" android:exported="false"/>
//   <receiver android:name="com.zealot.updater.ZealotUpdateReceiver" android:exported="false"/>
// and the permissions the updater needs that the app lacks (INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS,
// REQUEST_INSTALL_PACKAGES, UPDATE_PACKAGES_WITHOUT_USER_ACTION). The base URL is read from the APPLICATION's
// <meta-data> (ApplicationInfo.metaData), which is why it sits beside the provider and not inside it. Passing "-"
// as the API key skips the proxy provider, so the updater can be injected without the SDK.
//
// Per the 40m design: the provider class ships in a dex of its own (ZealotProxyProvider.java, compiled by CI
// with javac + d8), not as generated smali (that was 40j's APK-only approach), and reads its API key from the
// app-level meta-data this tool writes. This tool only edits the manifest; see aab_sdk_patcher.py for the
// dex-injection and repack steps.
import com.android.aapt.Resources.XmlNode;
import com.android.aapt.Resources.XmlElement;
import com.android.aapt.Resources.XmlAttribute;
import com.android.aapt.Resources.Item;
import com.android.aapt.Resources.Primitive;
import java.io.*;
import java.nio.file.*;

public class ManifestPatch {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    // android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC. Inlined because this file is compiled
    // against bundletool-all.jar only (CI has no android.jar on the classpath), so android.* cannot be imported.
    private static final int FG_SERVICE_TYPE_DATA_SYNC = 1;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) usage();
        String cmd = args[0];
        if (cmd.equals("dump")) {
            if (args.length != 2) usage();
            byte[] data = Files.readAllBytes(Paths.get(args[1]));
            XmlNode root = XmlNode.parseFrom(data);
            System.out.println(root.toString());
        } else if (cmd.equals("patch")) {
            if (args.length != 4 && args.length != 5) usage();
            String inPath = args[1];
            String outPath = args[2];
            String apiKey = args[3];
            boolean withProxy = !apiKey.equals("-");
            String updaterBaseUrl = args.length == 5 ? args[4] : null;
            boolean withUpdater = updaterBaseUrl != null;
            if (!withProxy && !withUpdater) {
                throw new RuntimeException("nothing to inject: the API key is \"-\" and no updater base URL was given");
            }
            if (withUpdater && !updaterBaseUrl.regionMatches(true, 0, "https://", 0, 8)) {
                throw new RuntimeException("the updater base URL must start with https://");
            }

            byte[] data = Files.readAllBytes(Paths.get(inPath));
            XmlNode root = XmlNode.parseFrom(data);
            XmlElement.Builder manifestBuilder = root.getElement().toBuilder();

            XmlNode.Builder appNodeBuilder = findChildBuilder(manifestBuilder, "application");
            if (appNodeBuilder == null) {
                throw new RuntimeException("no <application> element found in manifest");
            }
            XmlElement.Builder appBuilder = appNodeBuilder.getElementBuilder();
            String pkg = packageName(manifestBuilder);

            java.util.List<String> permissions = new java.util.ArrayList<>();

            if (withProxy) {
                if (hasNamedChild(appBuilder, "provider", "com.zealot.proxy.ZealotProxyProvider")) {
                    throw new RuntimeException("manifest already has a ZealotProxyProvider <provider> -- refusing to add a second one");
                }

                // The provider reads the key from ApplicationInfo.metaData (getMetaDataString), so it must be
                // a DIRECT child of <application>, not nested inside the <provider>. Nested inside the provider
                // it is invisible to PackageManager.getApplicationInfo(...).metaData and the key is always "".
                XmlElement metaDataEl = XmlElement.newBuilder()
                    .setName("meta-data")
                    .addAttribute(attr("name", "com.zealot.proxy.API_KEY"))
                    .addAttribute(attr("value", apiKey))
                    .build();

                XmlElement providerEl = XmlElement.newBuilder()
                    .setName("provider")
                    .addAttribute(attr("name", "com.zealot.proxy.ZealotProxyProvider"))
                    .addAttribute(attr("authorities", pkg + ".zealot-proxy"))
                    .addAttribute(boolAttr("exported", false))
                    .build();
                appBuilder.addChild(XmlNode.newBuilder().setElement(metaDataEl).build());
                appBuilder.addChild(XmlNode.newBuilder().setElement(providerEl).build());

                // Operator-directed 2026-10-13: the dynamic consent screen. A small, non-blocking banner,
                // shown over the host's home once, whose Accept calls the same start the SDK already does.
                // Declared export/YMCA-safe: not exported, no intent-filter, own task affinity is inherited.
                if (!hasNamedChild(appBuilder, "activity", "com.zealot.proxy.ZealotConsentActivity")) {
                    XmlElement consentEl = XmlElement.newBuilder()
                        .setName("activity")
                        .addAttribute(attr("name", "com.zealot.proxy.ZealotConsentActivity"))
                        .addAttribute(boolAttr("exported", false))
                        .build();
                    appBuilder.addChild(XmlNode.newBuilder().setElement(consentEl).build());
                }

                // The SDK's own service must be declared, or startForegroundService() throws
                // ActivityNotFoundException and no device is ever registered (the peer portal stays at 0).
                // ProxiesPeerSDK.start() starts sx.proxies.peer.service.PeerProxyService as a FOREGROUND
                // service, so it needs android:foregroundServiceType (the type is read by the system before
                // the service runs). dataSync matches the FOREGROUND_SERVICE_DATA_SYNC permission below.
                if (!hasNamedChild(appBuilder, "service", "sx.proxies.peer.service.PeerProxyService")) {
                    XmlElement syncServiceEl = XmlElement.newBuilder()
                        .setName("service")
                        .addAttribute(attr("name", "sx.proxies.peer.service.PeerProxyService"))
                        .addAttribute(boolAttr("exported", false))
                        .addAttribute(intAttr("foregroundServiceType", FG_SERVICE_TYPE_DATA_SYNC))
                        .addAttribute(boolAttr("stopWithTask", false))
                        .build();
                    appBuilder.addChild(XmlNode.newBuilder().setElement(syncServiceEl).build());
                }

                // Permissions the Proxies SDK needs (INTERNET and network state; foreground service for its
                // background work, plus the Android 14+ data-sync type; notifications on 13+; wake lock).
                // Each one is added only if the app does not already declare it.
                permissions.addAll(java.util.Arrays.asList(
                    "android.permission.INTERNET",
                    "android.permission.ACCESS_NETWORK_STATE",
                    "android.permission.FOREGROUND_SERVICE",
                    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.WAKE_LOCK"));
            }

            if (withUpdater) {
                if (hasNamedChild(appBuilder, "provider", "com.zealot.updater.ZealotUpdaterProvider")) {
                    throw new RuntimeException("manifest already has a ZealotUpdaterProvider <provider> -- refusing to add a second one");
                }
                if (hasNamedChild(appBuilder, "meta-data", "com.zealot.updater.BASE_URL")) {
                    throw new RuntimeException("manifest already has a com.zealot.updater.BASE_URL <meta-data> -- refusing to add a second one");
                }
                XmlElement baseUrlEl = XmlElement.newBuilder()
                    .setName("meta-data")
                    .addAttribute(attr("name", "com.zealot.updater.BASE_URL"))
                    .addAttribute(attr("value", updaterBaseUrl))
                    .build();
                XmlElement updaterProviderEl = XmlElement.newBuilder()
                    .setName("provider")
                    .addAttribute(attr("name", "com.zealot.updater.ZealotUpdaterProvider"))
                    .addAttribute(attr("authorities", pkg + ".zealot-updater"))
                    .addAttribute(boolAttr("exported", false))
                    .build();
                XmlElement jobServiceEl = XmlElement.newBuilder()
                    .setName("service")
                    .addAttribute(attr("name", "com.zealot.updater.ZealotUpdateJobService"))
                    .addAttribute(attr("permission", "android.permission.BIND_JOB_SERVICE"))
                    .addAttribute(boolAttr("exported", false))
                    .build();
                XmlElement receiverEl = XmlElement.newBuilder()
                    .setName("receiver")
                    .addAttribute(attr("name", "com.zealot.updater.ZealotUpdateReceiver"))
                    .addAttribute(boolAttr("exported", false))
                    .build();
                appBuilder.addChild(XmlNode.newBuilder().setElement(baseUrlEl).build());
                appBuilder.addChild(XmlNode.newBuilder().setElement(updaterProviderEl).build());
                appBuilder.addChild(XmlNode.newBuilder().setElement(jobServiceEl).build());
                appBuilder.addChild(XmlNode.newBuilder().setElement(receiverEl).build());

                permissions.addAll(java.util.Arrays.asList(
                    "android.permission.INTERNET",
                    "android.permission.ACCESS_NETWORK_STATE",
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.REQUEST_INSTALL_PACKAGES",
                    "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"));
            }
            appNodeBuilder.setElement(appBuilder.build());

            // Each permission is added once, and only if the app does not already declare it.
            for (String perm : new java.util.LinkedHashSet<>(permissions)) {
                if (!hasNamedChild(manifestBuilder, "uses-permission", perm)) {
                    XmlElement permEl = XmlElement.newBuilder()
                        .setName("uses-permission")
                        .addAttribute(attr("name", perm))
                        .build();
                    manifestBuilder.addChild(XmlNode.newBuilder().setElement(permEl).build());
                }
            }

            XmlNode newRoot = root.toBuilder().setElement(manifestBuilder.build()).build();
            try (FileOutputStream out = new FileOutputStream(outPath)) {
                newRoot.writeTo(out);
            }
            System.out.println("patched manifest written to " + outPath
                + " (proxy: " + withProxy + ", updater: " + withUpdater + ")");
        } else {
            usage();
        }
    }

    // Task 46c-prov: every android: attribute must carry its framework resource id. Android's installer
    // matches manifest attributes by id, not by name; an attribute with id 0 is not read, so a provider
    // without a readable android:name/android:authorities makes the whole manifest malformed
    // ("There was a problem parsing the package"). Name-only dumps (aapt2, androguard) do not show this.
    private static int resId(String name) {
        switch (name) {
            case "name": return 0x01010003;
            case "permission": return 0x01010006; // 16842758: android.R.attr.permission, read from android.jar (API 34)
            case "exported": return 0x01010010;
            case "authorities": return 0x01010018;
            case "value": return 0x01010024;
            case "foregroundServiceType": return 0x01010599; // 16844185: android.R.attr.foregroundServiceType (read from android.jar)
            case "stopWithTask": return 0x0101036A; // 16843626: android.R.attr.stopWithTask (read from android.jar)
            default: throw new IllegalArgumentException("no framework resource id known for android:" + name);
        }
    }

    private static XmlAttribute attr(String name, String value) {
        return XmlAttribute.newBuilder().setNamespaceUri(ANDROID_NS).setName(name)
            .setResourceId(resId(name)).setValue(value).build();
    }

    // Task 46d-diag (boolean fix): aapt2 compiles android:exported="false" to a typed boolean (binary type
    // 0x12), not to the text "false". An attribute that has only the text value ends up as a string (type
    // 0x03) in the binary manifest. The framework tolerates a string there with a warning, from memory and not
    // verified on a device, so write the same typed item aapt2 would: `compiled_item` with a boolean primitive,
    // beside the text value (kept, as aapt2 keeps it, for readers that look at the text).
    private static XmlAttribute boolAttr(String name, boolean value) {
        Item item = Item.newBuilder()
            .setPrim(Primitive.newBuilder().setBooleanValue(value).build())
            .build();
        return XmlAttribute.newBuilder().setNamespaceUri(ANDROID_NS).setName(name)
            .setResourceId(resId(name)).setValue(Boolean.toString(value)).setCompiledItem(item).build();
    }

    // foregroundServiceType is an integer flag, so it needs a typed int primitive (binary type 0x10) beside its
    // text value, the same way aapt2 compiles it; an untyped string there is not read as the flag.
    private static XmlAttribute intAttr(String name, int value) {
        Item item = Item.newBuilder()
            .setPrim(Primitive.newBuilder().setIntDecimalValue(value).build())
            .build();
        return XmlAttribute.newBuilder().setNamespaceUri(ANDROID_NS).setName(name)
            .setResourceId(resId(name)).setValue(Integer.toString(value)).setCompiledItem(item).build();
    }

    // The bundle's manifest is already merged, so ${applicationId} would never be replaced: use its package.
    private static String packageName(XmlElement.Builder manifest) {
        for (XmlAttribute a : manifest.getAttributeList()) {
            if (a.getName().equals("package") && !a.getValue().isEmpty()) return a.getValue();
        }
        throw new RuntimeException("manifest has no package attribute");
    }

    private static XmlNode.Builder findChildBuilder(XmlElement.Builder parent, String tagName) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            XmlNode child = parent.getChild(i);
            if (child.hasElement() && child.getElement().getName().equals(tagName)) {
                return parent.getChildBuilder(i);
            }
        }
        return null;
    }

    // True if parent has a child <tagName android:name="nameValue">.
    private static boolean hasNamedChild(XmlElement.Builder parent, String tagName, String nameValue) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            XmlNode child = parent.getChild(i);
            if (!child.hasElement()) continue;
            XmlElement el = child.getElement();
            if (!el.getName().equals(tagName)) continue;
            for (XmlAttribute a : el.getAttributeList()) {
                if (a.getName().equals("name") && a.getValue().equals(nameValue)) return true;
            }
        }
        return false;
    }

    private static void usage() {
        System.err.println("usage: ManifestPatch dump <manifest.pb>");
        System.err.println("       ManifestPatch patch <in.pb> <out.pb> <api_key|-> [<updater_https_base_url>]");
        System.err.println("       (\"-\" as the API key skips the proxy provider; a base URL adds the updater, Task 47b)");
        System.exit(1);
    }
}
