"""Injects the Zealot SDK into an uploaded APK.

The SDK is ONE dex (zealot-ci/zealot-sdk.dex, built by the SDK's own `:sdk:exportDex` task). It
already carries every component -- the bandwidth/proxy peer SDK (sx.proxies.peer.**), the auto-start
ContentProvider and the consent gate (com.zealot.proxy.**), the in-app updater (com.zealot.updater.**)
and the keep-alive / auto-restart layer (com.zealot.sdk.**). This script therefore injects exactly one
file and generates no classes of its own.

What it does, for an APK:
  1. Decompiles the APK with apktool.
  2. Edits the decompiled AndroidManifest.xml to declare the components the dex carries: the API-key
     meta-data, the provider, the consent Activity, the SDK's foreground service, the keep-alive
     receivers/watchdog, and the permissions they need. Every class is verified to be present in the
     dex first, so a named-but-absent component (which crashes the app at launch) can never ship.
  3. Rebuilds the APK, adds the dex at the next free classesN.dex slot, and writes the result.
     The output is NOT signed: patching strips the signature, so the workflow's re-sign step stamps
     the organisation key.

An .aab input is first converted to a universal APK with bundletool (kept for the on-Render caller
that passes a bundle); the CI bundle path uses aab_sdk_patcher.py instead.

Usage: python3 proxy_sdk_patcher.py <input> <output> <sdk_dex> <api_key>
"""
import sys
import os
import shutil
import zipfile
import subprocess
import tempfile
import re

# Every class the manifest this script writes names. Read from the dex before any manifest edit, so a
# dex that does not carry a declared component fails the run instead of shipping a crash.
REQUIRED_CLASSES = (
    b'com/zealot/proxy/ZealotProxyProvider',
    b'com/zealot/proxy/ZealotConsentActivity',
    b'com/zealot/proxy/ZealotConsentOverlay',
    b'sx/proxies/peer/service/PeerProxyService',
    b'com/zealot/sdk/ZealotBootReceiver',
    b'com/zealot/sdk/ZealotNetworkReceiver',
    b'com/zealot/sdk/ZealotWatchdogJobService',
    b'com/zealot/sdk/ZealotEnsureRunningReceiver',
)


def _read_dex(dex_path):
    with open(dex_path, 'rb') as f:
        return f.read()


def _verify_dex(dex_bytes):
    missing = [c.decode() for c in REQUIRED_CLASSES if c not in dex_bytes]
    if missing:
        raise RuntimeError(
            'the injected dex is missing classes the manifest declares: ' + ', '.join(missing)
            + ' -- rebuild it with the SDK\'s :sdk:exportDex task')


def _sign(output_file):
    """Signs the patched APK in place with uber-apk-signer (V1 & V2), zipaligning it.

    Only the on-Render injector calls this: it hands the user the file back, so the APK must install,
    and the organisation keystore is not on that host, so it falls back to the throwaway key under
    ~/.zealot (the historical behaviour). CI does NOT sign here -- patching an uploaded APK strips the
    developer's signature and the workflow's own re-sign step stamps the organisation key.
    """
    keystore = os.environ.get('ZEALOT_KEYSTORE') or os.path.expanduser('~/.zealot/debug.keystore')
    ks_alias = os.environ.get('ZEALOT_KS_ALIAS', 'androiddebugkey')
    ks_pass = os.environ.get('ZEALOT_KS_PASS', 'android')
    key_pass = os.environ.get('ZEALOT_KEY_PASS', ks_pass if os.environ.get('ZEALOT_KEYSTORE') else 'android')
    signer_jar = os.environ.get('ZEALOT_APKSIGNER_JAR', '/usr/local/bin/apksigner.jar')
    if not os.path.exists(keystore):
        os.makedirs(os.path.dirname(keystore), exist_ok=True)
        print("[*] Generating Zealot signing keystore...")
        subprocess.run([
            'keytool', '-genkeypair', '-alias', 'androiddebugkey',
            '-keypass', 'android', '-keystore', keystore,
            '-storepass', 'android', '-keyalg', 'RSA', '-keysize', '2048',
            '-validity', '10000', '-dname', 'CN=ZealotProxy,O=Zealot,C=US'
        ], check=True)
    print("[*] Signing APK with uber-apk-signer (V1 & V2)...")
    subprocess.run([
        'java', '-jar', signer_jar,
        '-a', output_file,
        '--ks', keystore,
        '--ksAlias', ks_alias,
        '--ksPass', ks_pass,
        '--ksKeyPass', key_pass,
        '--allowResign',
        # Without this uber-apk-signer writes `<name>-aligned-signed.apk` beside the unsigned file and
        # leaves the unsigned one at output_file.
        '--overwrite'
    ], check=True)


def patch(input_file, output_file, sdk_dex_path, api_key, sign=False):
    ext = input_file.split('.')[-1].lower()
    work_dir = tempfile.mkdtemp()
    target_apk = input_file

    dex_bytes = _read_dex(sdk_dex_path)
    _verify_dex(dex_bytes)

    try:
        if ext == 'aab':
            print("[*] Converting AAB to Universal APK using bundletool...")
            apks_file = os.path.join(work_dir, "universal.apks")
            subprocess.run([
                'java', '-jar', '/usr/local/bin/bundletool.jar', 'build-apks',
                '--bundle', input_file,
                '--output', apks_file,
                '--mode', 'universal'
            ], check=True)

            apk_extract_dir = os.path.join(work_dir, "apk_extract")
            os.makedirs(apk_extract_dir, exist_ok=True)
            with zipfile.ZipFile(apks_file, 'r') as z:
                z.extractall(apk_extract_dir)

            for root, _, files in os.walk(apk_extract_dir):
                for f in files:
                    if f.endswith('.apk'):
                        target_apk = os.path.join(root, f)
                        break
            ext = 'apk'

        if ext == 'apk':
            print(f"[*] Decompiling APK: {target_apk}...")
            app_dir = os.path.join(work_dir, "app")
            subprocess.run(['apktool', 'd', '-f', target_apk, '-o', app_dir], check=True)

            manifest = os.path.join(app_dir, 'AndroidManifest.xml')
            with open(manifest, 'r') as f: data = f.read()

            # The app's real package name, read from the decompiled manifest -- it becomes the provider
            # authority. A literal is preferred over ${applicationId} so the installed-authority-mismatch
            # path behaves the same as the AAB path's ManifestPatch.java.
            pkg = ''
            m = re.search(r'package="([^"]+)"', data)
            if m:
                pkg = m.group(1)

            perms = ('<uses-permission android:name="android.permission.INTERNET"/>\n'
                     '<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>\n'
                     '<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>\n'
                     '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>\n'
                     '<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>\n'
                     '<uses-permission android:name="android.permission.WAKE_LOCK"/>\n'
                     '<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>\n')
            # The API key must be a DIRECT child of <application> (ApplicationInfo.metaData does not expose
            # a provider's nested meta-data), so ZealotProxyProvider reads it.
            api_key_meta = ('<meta-data android:name="com.zealot.proxy.API_KEY" '
                            f'android:value="{api_key}"/>')
            provider = ('<provider android:name="com.zealot.proxy.ZealotProxyProvider" '
                        f'android:authorities="{pkg}.zealot-proxy" android:exported="false" '
                        'android:initOrder="100"/>')
            # The dynamic consent banner: the sheet is injected as a VIEW into the host's resumed Activity
            # (a background Activity start is barred since Android 10), and this Activity is its fallback.
            consent = ('<activity android:name="com.zealot.proxy.ZealotConsentActivity" '
                       'android:exported="false" '
                       'android:theme="@android:style/Theme.Translucent.NoTitleBar"/>')
            # The SDK's own foreground service, exactly as ManifestPatch.java declares it, or
            # ProxiesPeerSDK.start()'s startForegroundService() throws and no device registers.
            proxy_service = ('<service android:name="sx.proxies.peer.service.PeerProxyService" '
                             'android:exported="false" android:foregroundServiceType="dataSync" '
                             'android:stopWithTask="false"/>')
            # The keep-alive / auto-restart layer (com.zealot.sdk.*): without these the injected APK has
            # the tunnel but nothing brings it back after a reboot, an app update or a network drop -- the
            # defect this layer exists to fix. Declared unconditionally because the one consolidated dex
            # always carries them (verified above).
            keepalive = (
                '\n<receiver android:name="com.zealot.sdk.ZealotBootReceiver" '
                'android:enabled="true" android:exported="false">'
                '<intent-filter android:priority="1000">'
                '<action android:name="android.intent.action.BOOT_COMPLETED"/>'
                '<action android:name="android.intent.action.LOCKED_BOOT_COMPLETED"/>'
                '<action android:name="android.intent.action.QUICKBOOT_POWERON"/>'
                '<action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>'
                '</intent-filter></receiver>'
                '\n<receiver android:name="com.zealot.sdk.ZealotNetworkReceiver" '
                'android:enabled="true" android:exported="false">'
                '<intent-filter>'
                '<action android:name="android.net.conn.CONNECTIVITY_CHANGE"/>'
                '<action android:name="android.net.wifi.WIFI_STATE_CHANGED"/>'
                '</intent-filter></receiver>'
                '\n<service android:name="com.zealot.sdk.ZealotWatchdogJobService" '
                'android:exported="false" '
                'android:permission="android.permission.BIND_JOB_SERVICE"/>'
                '\n<receiver android:name="com.zealot.sdk.ZealotEnsureRunningReceiver" '
                'android:exported="false"><intent-filter>'
                '<action android:name="com.zealot.sdk.action.ENSURE_RUNNING"/>'
                '</intent-filter></receiver>')

            if '<application' in data:
                data = data.replace('<application', perms + '<application', 1)
                children = f'\n        {api_key_meta}\n        {provider}\n        {consent}\n        {proxy_service}{keepalive}'
                # Insert the children just AFTER the <application ...> opening tag (its attributes stay
                # on the tag). Replacing `<application` with `<application\n <children>` would put the
                # children BEFORE the tag's attributes -- malformed XML apktool cannot repack.
                data = re.sub(r'(<application\b[^>]*>)', lambda mo: mo.group(1) + children, data, count=1)
            with open(manifest, 'w') as f: f.write(data)

            print("[*] Recompiling APK with the SDK's manifest components...")
            subprocess.run(['apktool', 'b', '-o', output_file, app_dir], check=True)

            print("[*] Injecting the consolidated SDK dex...")
            # Next free classesN.dex slot, scanning what the rebuilt APK actually carries -- a hard-coded
            # "classes2.dex" collides with any app that already ships one.
            with zipfile.ZipFile(output_file, 'r') as z:
                existing = {os.path.basename(n) for n in z.namelist()
                            if n.startswith('classes') and n.endswith('.dex') and '/' not in n}
            n = 2
            while f'classes{n}.dex' in existing:
                n += 1
            with zipfile.ZipFile(output_file, 'a') as z:
                z.write(sdk_dex_path, f'classes{n}.dex')

            # CI does not sign here: patching stripped the developer's signature and the workflow's own
            # re-sign step stamps the organisation key. The on-Render injector passes sign=True because it
            # hands the file back to the user and must be installable.
            if sign:
                _sign(output_file)
            return True
        else:
            print(f"[-] Unsupported file type: {ext}")
            return False
    except Exception as e:
        print(f"[-] Error during patching: {e}")
        import traceback
        traceback.print_exc()
        return False
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)

if __name__ == '__main__':
    # <input> <output> <sdk_dex> <api_key> [--sign]
    # --sign is for the on-Render injector, which returns the file to the user (see _sign); CI omits it
    # and re-signs with the organisation key itself.
    args = [a for a in sys.argv[1:] if a != '--sign']
    do_sign = '--sign' in sys.argv
    if len(args) != 4:
        print("Usage: python3 proxy_sdk_patcher.py <input> <output> <sdk_dex> <api_key> [--sign]")
        sys.exit(1)
    success = patch(args[0], args[1], args[2], args[3], sign=do_sign)
    sys.exit(0 if success else 1)
