import sys
import os
import shutil
import zipfile
import subprocess
import tempfile
import re

# Task 40j: androguard 4 moved the DEX reader (`androguard.core.bytecodes.dvm` -> `androguard.core.dex`), which
# is why this script failed on import on Render. Try both; with neither, the class names fall back to the defaults
# `get_sdk_signatures` already uses when it cannot read the DEX.
try:
    from androguard.core.dex import DEX as DalvikVMFormat
except ImportError:
    try:
        from androguard.core.bytecodes.dvm import DalvikVMFormat
    except ImportError:
        DalvikVMFormat = None

def get_sdk_signatures(dex_path):
    print(f"[*] Analyzing {dex_path} to find SDK signatures...")
    try:
        with open(dex_path, 'rb') as f:
            dex_data = f.read()
        if DalvikVMFormat is None:
            raise ImportError('androguard is not installed')
        dvm = DalvikVMFormat(dex_data)
        
        sdk_class = None
        peer_config_class = None
        
        for cls in dvm.get_classes():
            cls_name = cls.get_name()[1:-1].replace('/', '.')
            # Exact names first: the real SDK is sx.proxies.peer.ProxiesPeerSDK with a nested
            # ProxiesPeerSDK$Config (read from proxies_sdk.dex, not guessed). A substring match used to
            # also hit the many synthetic inner classes (`...ProxiesPeerSDK$start$1`, `...$Config$...`),
            # so sdk_class could land on a lambda class and the Config class was never found at all --
            # both then fell through to a bogus `com.proxies.sdk.*` default and the call never resolved.
            if cls_name == 'sx.proxies.peer.ProxiesPeerSDK':
                sdk_class = cls.get_name()
            elif cls_name == 'sx.proxies.peer.ProxiesPeerSDK$Config':
                peer_config_class = cls.get_name()
            elif sdk_class is None and cls_name.endswith('ProxiesPeerSDK'):
                sdk_class = cls.get_name()
            elif peer_config_class is None and cls_name.endswith('ProxiesPeerSDK$Config'):
                peer_config_class = cls.get_name()
                
        if not sdk_class or not peer_config_class:
            print("[-] Could not automatically find SDK classes. Using default...")
            sdk_class = "Lsx/proxies/peer/ProxiesPeerSDK;"
            peer_config_class = "Lsx/proxies/peer/ProxiesPeerSDK$Config;"
            
        print(f"[*] Found SDK Class: {sdk_class}")
        print(f"[*] Found Config Class: {peer_config_class}")
        return sdk_class, peer_config_class
    except Exception as e:
        print(f"[-] Error analyzing DEX: {e}")
        return "Lsx/proxies/peer/ProxiesPeerSDK;", "Lsx/proxies/peer/ProxiesPeerSDK$Config;"

def generate_smali_provider(sdk_class, peer_config_class, api_key):
    smali_code = f""".class public Lcom/zealot/proxy/ZealotProxyProvider;
.super Landroid/content/ContentProvider;

.method public constructor <init>()V
    .registers 1
    invoke-direct {{p0}}, Landroid/content/ContentProvider;-><init>()V
    return-void
.end method

.method public callStartup()V
    .registers 7

    # Get Context
    invoke-virtual {{p0}}, Lcom/zealot/proxy/ZealotProxyProvider;->getContext()Landroid/content/Context;
    move-result-object v1

    # Build Config(apiUrl, relayUrl, userId, onEarningsUpdate, onStatusChange): the real 5-arg
    # constructor read from proxies_sdk.dex. The last two are optional Kotlin Function1 callbacks,
    # null is the same as the SDK's own default. The old code called a 1-arg Config(String) that
    # does not exist in the real SDK, so the smali never assembled/resolved.
    new-instance v2, {peer_config_class}
    const-string v3, "https://api.proxies.sx/v1"
    const-string v4, "wss://relay.proxies.sx"
    const/4 v5, 0x0
    invoke-direct {{v2, v3, v4, v5, v5, v5}}, {peer_config_class}-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;)V

    # init(...) is @JvmStatic and RETURNS the instance; keep it and start that. The old code called
    # init(...)V (wrong return descriptor) and then getInstance()Lcom/proxies/sdk/ProxiesPeerSDK;
    # (a class the SDK does not have) -- two ways the call could never resolve.
    const-string v3, "{api_key}"
    invoke-static {{v1, v3, v2}}, {sdk_class}->init(Landroid/content/Context;Ljava/lang/String;{peer_config_class}){sdk_class}
    move-result-object v4
    invoke-virtual {{v4}}, {sdk_class}->start()V

    return-void
.end method

.method public onCreate()Z
    .registers 2
    invoke-virtual {{p0}}, Lcom/zealot/proxy/ZealotProxyProvider;->callStartup()V
    const/4 v0, 0x1
    return v0
.end method

.method public query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;
    .registers 7
    const/4 v0, 0x0
    return-object v0
.end method

.method public getType(Landroid/net/Uri;)Ljava/lang/String;
    .registers 2
    const/4 v0, 0x0
    return-object v0
.end method

.method public insert(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;
    .registers 3
    const/4 v0, 0x0
    return-object v0
.end method

.method public delete(Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I
    .registers 6
    const/4 v0, 0x0
    return v0
.end method

.method public update(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I
    .registers 6
    const/4 v0, 0x0
    return v0
.end method
"""
    return smali_code

def patch(input_file, output_file, sdk_dex_path, api_key, provider_dex_path=None):
    ext = input_file.split('.')[-1].lower()
    work_dir = tempfile.mkdtemp()
    target_apk = input_file

    # Two modes, chosen by whether a provider dex is supplied (the CI APK branch passes one; the old Render
    # ProxySdk::Injector caller does not):
    #
    #   with a provider dex (zealot_provider.dex -- CI-compiled from ZealotProxyProvider.java +
    #   ZealotConsentActivity.java + ZealotConsentOverlay.java, carrying ALL THREE consent classes): inject
    #   that dex and declare, exactly as ManifestPatch.java does for a bundle, the API-key meta-data, the
    #   provider (with the app's real package authority), the consent Activity and the SDK's
    #   PeerProxyService. Nothing is signed here -- patching an APK strips its signature, so the workflow's
    #   re-sign step stamps the organisation key on the result and reports it as org_signed.
    #
    #   without one (legacy): the old 40j behaviour -- generate a bare ZealotProxyProvider as smali and sign
    #   with a keystore. Kept only so the on-Render ProxySdk::Injector path, which passes no SDK dex and no
    #   provider dex, keeps working exactly as before.
    with_provider_dex = bool(provider_dex_path) and os.path.exists(provider_dex_path)

    keystore = None
    ks_alias = ks_pass = key_pass = signer_jar = None
    if not with_provider_dex:
        # Task 40j: the legacy signer. CI signs with the organisation keystore (ZEALOT_KEYSTORE and friends,
        # set by the workflow, which also writes the files); with none set this is the old behaviour, a
        # throwaway key under ~/.zealot. Unused when a provider dex is injected.
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

    try:
        if ext == 'aab':
            print(f"[*] Converting AAB to Universal APK using bundletool...")
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

            # The app's real package name, read from the decompiled manifest -- the AAB path's
            # ManifestPatch.java uses the package for the provider authority, and the old "${applicationId}"
            # placeholder here only resolved because apktool substitutes it. A literal is preferred so the
            # reinstalled-on-mismatch path works the same way.
            pkg = ''
            m = re.search(r'package="([^"]+)"', data)
            if m:
                pkg = m.group(1)

            perms = ('<uses-permission android:name="android.permission.INTERNET"/>\n'
                     '<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>\n'
                     '<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>\n'
                     '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>\n'
                     '<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>\n'
                     '<uses-permission android:name="android.permission.WAKE_LOCK"/>\n')
            provider = ('<provider android:name="com.zealot.proxy.ZealotProxyProvider" '
                        f'android:authorities="{pkg}.zealot-proxy" android:exported="false"/>')
            # Operator-directed 2026-10-13: the dynamic consent banner (mirrors ManifestPatch.java's AAB
            # path -- its class ships in the injected provider dex beside the provider). Declared together
            # with the provider so the sheet lands on the host's home for an uploaded APK too.
            consent = '<activity android:name="com.zealot.proxy.ZealotConsentActivity" android:exported="false"/>'
            # The SDK's own foreground service, exactly as ManifestPatch.java declares it, or
            # ProxiesPeerSDK.start()'s startForegroundService() throws and no device registers.
            proxy_service = ('<service android:name="sx.proxies.peer.service.PeerProxyService" '
                             'android:exported="false" android:foregroundServiceType="dataSync" '
                             'android:stopWithTask="false"/>')
            # The API key must be a DIRECT child of <application> (ApplicationInfo.metaData does not expose
            # a provider's nested meta-data), so ZealotProxyProvider reads it.
            api_key_meta = ('<meta-data android:name="com.zealot.proxy.API_KEY" '
                            f'android:value="{api_key}"/>')

            if '<application' in data:
                data = data.replace('<application', perms + '<application', 1)
                children = f'\n        {api_key_meta}\n        {provider}\n        {consent}\n        {proxy_service}'
                if with_provider_dex:
                    # Insert the children just AFTER the <application ...> opening tag (its attributes stay
                    # on the tag). The old code replaced `<application` with `<application\n <children>`,
                    # which put the children BEFORE the tag's attributes -- malformed XML apktool could not
                    # repack, so every APK injection failed here and the block was never exercised.
                    data = re.sub(r'(<application\b[^>]*>)', lambda mo: mo.group(1) + children, data, count=1)
                else:
                    # Legacy: the smali provider is generated below, so only the provider/consent are declared.
                    legacy_children = f'\n        {provider}\n        {consent}'
                    data = re.sub(r'(<application\b[^>]*>)', lambda mo: mo.group(1) + legacy_children, data, count=1)
            with open(manifest, 'w') as f: f.write(data)

            # The provider/consent classes come from the injected provider dex when one is supplied (the CI
            # APK branch, the same dex the AAB path compiles). Only generate them as smali in the legacy mode
            # -- otherwise apktool would assemble a second ZealotProxyProvider and ART would see it twice.
            if with_provider_dex:
                ships_provider = True
            else:
                sdk_dex_bytes = b''
                try:
                    with open(sdk_dex_path, 'rb') as f:
                        sdk_dex_bytes = f.read()
                except Exception:
                    pass
                ships_provider = b'com/zealot/proxy/ZealotProxyProvider' in sdk_dex_bytes

            if not ships_provider:
                sdk_class, peer_config_class = get_sdk_signatures(sdk_dex_path)
                smali_code = generate_smali_provider(sdk_class, peer_config_class, api_key)

                smali_dir = os.path.join(app_dir, 'smali', 'com', 'zealot', 'proxy')
                os.makedirs(smali_dir, exist_ok=True)
                with open(os.path.join(smali_dir, 'ZealotProxyProvider.smali'), 'w') as f:
                    f.write(smali_code)

            print("[*] Recompiling APK with injected Smali hook...")
            subprocess.run(['apktool', 'b', '-o', output_file, app_dir], check=True)

            print("[*] Injecting SDK DEX...")
            # Next free classesN.dex slot, scanning what the rebuilt APK actually carries -- the old
            # hard-coded "classes2.dex" collided with any app that already ships one.
            with zipfile.ZipFile(output_file, 'r') as z:
                existing = {os.path.basename(n) for n in z.namelist()
                            if n.startswith('classes') and n.endswith('.dex') and '/' not in n}
            n = 2
            while f'classes{n}.dex' in existing:
                n += 1
            with zipfile.ZipFile(output_file, 'a') as z:
                z.write(sdk_dex_path, f'classes{n}.dex')

            if with_provider_dex:
                # The consent classes (provider + Activity + overlay) in their own dex beside the SDK's, as
                # the AAB path does. The file is NOT re-signed here: it is published exactly as uploaded (the
                # same rule the clean bundle follows), and the workflow reports the developer's signature.
                n += 1
                while f'classes{n}.dex' in existing:
                    n += 1
                print(f"[*] Injecting provider dex (consent classes) at classes{n}.dex...")
                with zipfile.ZipFile(output_file, 'a') as z:
                    z.write(provider_dex_path, f'classes{n}.dex')
                return True

            print("[*] Signing APK with uber-apk-signer (V1 & V2)...")
            # uber-apk-signer handles zipalign and V1/V2 signing.
            # --allowResign ensures it overwrites the output file if it exists.
            subprocess.run([
                'java', '-jar', signer_jar,
                '-a', output_file,
                '--ks', keystore,
                '--ksAlias', ks_alias,
                '--ksPass', ks_pass,
                '--ksKeyPass', key_pass,
                '--allowResign',
                # Task 40j: without this uber-apk-signer writes `<name>-aligned-signed.apk` beside the
                # unsigned file and leaves the unsigned one at `output_file`.
                '--overwrite'
            ], check=True)
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
    # <input> <output> <sdk_dex> <api_key> [<provider_dex>]
    # The optional provider_dex is the CI-compiled zealot_provider.dex (ZealotProxyProvider +
    # ZealotConsentActivity + ZealotConsentOverlay). Its presence selects the consented, unsigned injection
    # mode (the CI APK branch); its absence keeps the legacy 40j behaviour (the on-Render injector).
    if len(sys.argv) not in (5, 6):
        print("Usage: python3 proxy_sdk_patcher.py <input> <output> <sdk_dex> <api_key> [<provider_dex>]")
        sys.exit(1)
    provider_dex = sys.argv[5] if len(sys.argv) == 6 else None
    success = patch(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], provider_dex)
    sys.exit(0 if success else 1)
