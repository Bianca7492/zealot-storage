"""
Task 40n-a: AAB SDK patcher (prototype).

Takes an app bundle (.aab) as it was uploaded/stored (the "clean" bundle -- the only file Play
ever sees) and produces a PATCHED COPY with the Proxies SDK's manifest hooks and dex added. The
input file is opened read-only and copied before any edit; nothing here ever writes to it, so the
"clean bundle is byte-identical to its input" invariant (see handover.md, Task 40n-a acceptance
check) holds by construction, not by a check this script runs.

What it does, in order:
  1. Copies the input .aab so the original is never touched.
  2. Extracts base/manifest/AndroidManifest.xml (protobuf, not binary AXML -- see
     lib/aab_manifest_patch/ManifestPatch.java for why that distinction matters) and patches it
     with ManifestPatch.java: adds the <provider> + API-key <meta-data> and the INTERNET
     <uses-permission>, refusing (raising) if a ZealotProxyProvider provider is already present.
  3. Adds the SDK dex at the next free base/dex/classesN.dex slot (classes.dex is always slot 1;
     this never collides with an app that already ships classes2.dex, classes3.dex, etc., unlike
     40j's APK-only patcher which hard-codes classes2.dex).
  4. Writes the result to the given output path. Nothing is signed here -- signing an AAB itself
     is not how Play distribution authentication works; call bundletool build-apks with --ks to
     get a signed universal APK from the patched bundle (see verify_with_bundletool below, used
     by the test script, not by the patch step itself).

Not done here (left to 40n-b, wiring this into the CI stage-2 job):
  - no network/API calls, no stage-2 wiring, no choice of which API key to use (passed in).
  - no signing. A patched bundle is unsigned exactly like the clean one it's derived from.

Requires: java (any JDK 11+) and a bundletool-all-*.jar (BUNDLETOOL_JAR env var, or pass
--bundletool-jar). Nothing else -- no apktool, no Android SDK, no aapt2 binary. The Java helper
(ManifestPatch.java) is compiled once into a sibling build/ directory and reused.
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST_PATCH_SRC_DIR = os.path.join(HERE, 'aab_manifest_patch')
MANIFEST_PATCH_BUILD_DIR = os.path.join(MANIFEST_PATCH_SRC_DIR, 'build')


def _ensure_manifest_patch_compiled(bundletool_jar):
    """Compiles ManifestPatch.java against the bundletool jar's bundled aapt proto classes, if
    not already compiled. Cheap (~1s); re-run is harmless, so this is not cached across the
    bundletool jar changing (no version stamp kept -- if the jar is swapped, delete build/)."""
    class_file = os.path.join(MANIFEST_PATCH_BUILD_DIR, 'ManifestPatch.class')
    if os.path.exists(class_file):
        return
    os.makedirs(MANIFEST_PATCH_BUILD_DIR, exist_ok=True)
    src = os.path.join(MANIFEST_PATCH_SRC_DIR, 'ManifestPatch.java')
    subprocess.run(
        ['javac', '-cp', bundletool_jar, src, '-d', MANIFEST_PATCH_BUILD_DIR],
        check=True,
    )


def _patch_manifest(manifest_pb_path, output_pb_path, api_key, bundletool_jar, updater_base_url=None,
                    store_name=None, store_package=None, sdk_dex_paths=None):
    _ensure_manifest_patch_compiled(bundletool_jar)
    cp = os.pathsep.join([MANIFEST_PATCH_BUILD_DIR, bundletool_jar])
    cmd = ['java', '-cp', cp, 'ManifestPatch', 'patch', manifest_pb_path, output_pb_path, api_key]
    if updater_base_url:
        # Task 47b/47c: the updater's provider, service, receiver, base-URL meta-data and permissions.
        cmd.append(updater_base_url)
        # Task 47j: tenant branding for the injected in-app update dialog. Positional (args 6 and 7), so the
        # base URL must be present for them to be read; written only when non-empty.
        if store_name or store_package:
            cmd.append(store_name or '')
            cmd.append(store_package or '')
    # The keep-alive components (receivers, watchdog) are declared only when the dex being injected
    # actually carries them. Passed by environment so the positional arguments stay as they are.
    env = dict(os.environ)
    env['ZEALOT_SDK_DEX_PATHS'] = os.pathsep.join(sdk_dex_paths or [])
    subprocess.run(cmd, check=True, env=env)


def _next_free_dex_name(aab_zip):
    """base/dex/classes.dex is always present and unnumbered; classes2.dex, classes3.dex, ...
    follow. Returns the first free name, scanning what's actually in the bundle rather than
    assuming classes2.dex is free (40j's APK patcher hard-codes classes2.dex and collides if the
    app already ships one -- see handover.md 40m notes)."""
    existing = set()
    for name in aab_zip.namelist():
        if name.startswith('base/dex/classes') and name.endswith('.dex'):
            existing.add(os.path.basename(name))
    if 'classes.dex' not in existing:
        raise RuntimeError('base/dex/classes.dex not found in bundle -- not a normal app bundle?')
    n = 2
    while f'classes{n}.dex' in existing:
        n += 1
    return f'classes{n}.dex'


def patch(input_aab, output_aab, sdk_dex_paths, api_key, bundletool_jar, updater_base_url=None,
          store_name=None, store_package=None):
    """Produces output_aab as a patched copy of input_aab. Raises on any failure; never writes to
    input_aab. Accepts a list of DEX files to inject.

    Task 47c: `api_key` of "-" skips the proxy SDK's provider (the updater can be injected alone), and
    `updater_base_url` (https, no trailing path) adds the updater's manifest entries; the caller passes the
    updater's compiled dex in `sdk_dex_paths` like any other. Refuses when there is nothing to inject.

    Task 47j: `store_name` / `store_package` brand the injected in-app update dialog for the tenant."""
    if api_key == '-' and not updater_base_url:
        raise ValueError('nothing to inject: no API key and no updater base URL')
    if updater_base_url and not updater_base_url.lower().startswith('https://'):
        raise ValueError('the updater base URL must start with https://')
    if not os.path.exists(input_aab):
        raise FileNotFoundError(input_aab)
    if not isinstance(sdk_dex_paths, list):
        sdk_dex_paths = [sdk_dex_paths]
    for p in sdk_dex_paths:
        if not os.path.exists(p):
            raise FileNotFoundError(p)

    work_dir = tempfile.mkdtemp(prefix='aab_sdk_patch_')
    try:
        working_copy = os.path.join(work_dir, 'working.aab')
        shutil.copyfile(input_aab, working_copy)

        manifest_in = os.path.join(work_dir, 'manifest_in.pb')
        manifest_out = os.path.join(work_dir, 'manifest_out.pb')

        with zipfile.ZipFile(working_copy, 'r') as z:
            manifest_data = z.read('base/manifest/AndroidManifest.xml')
            existing_dex_names = set()
            for name in z.namelist():
                if name.startswith('base/dex/classes') and name.endswith('.dex'):
                    existing_dex_names.add(os.path.basename(name))
        with open(manifest_in, 'wb') as f:
            f.write(manifest_data)

        _patch_manifest(manifest_in, manifest_out, api_key, bundletool_jar, updater_base_url,
                        store_name, store_package, sdk_dex_paths)

        new_dex_mappings = []
        for dex_path in sdk_dex_paths:
            n = 2
            while f'classes{n}.dex' in existing_dex_names:
                n += 1
            dex_name = f'classes{n}.dex'
            existing_dex_names.add(dex_name)
            new_dex_mappings.append((dex_path, dex_name))

        with zipfile.ZipFile(working_copy, 'r') as zin, \
             zipfile.ZipFile(output_aab, 'w', zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename == 'base/manifest/AndroidManifest.xml':
                    with open(manifest_out, 'rb') as f:
                        data = f.read()
                zout.writestr(item, data)
            for dex_path, dex_name in new_dex_mappings:
                with open(dex_path, 'rb') as f:
                    zout.writestr(f'base/dex/{dex_name}', f.read())

        return {'dexes_added': [m[1] for m in new_dex_mappings]}
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)


def verify_with_bundletool(patched_aab, bundletool_jar, ks=None, ks_pass=None, ks_alias=None, key_pass=None):
    """Not part of the patch step. Runs bundletool build-apks --mode=universal on the patched
    bundle and returns the path to the resulting universal.apk, so a caller (or the test script)
    can confirm the patch actually builds and the manifest edit survives aapt2's own protobuf ->
    binary-AXML conversion. Raises CalledProcessError if bundletool rejects the bundle."""
    work_dir = tempfile.mkdtemp(prefix='aab_sdk_verify_')
    apks_path = os.path.join(work_dir, 'out.apks')
    cmd = ['java', '-jar', bundletool_jar, 'build-apks',
           '--bundle', patched_aab, '--output', apks_path, '--mode', 'universal', '--overwrite']
    if ks:
        cmd += ['--ks', ks, '--ks-pass', f'pass:{ks_pass}', '--ks-key-alias', ks_alias,
                '--key-pass', f'pass:{key_pass}']
    subprocess.run(cmd, check=True)
    extract_dir = os.path.join(work_dir, 'extracted')
    with zipfile.ZipFile(apks_path, 'r') as z:
        z.extractall(extract_dir)
    for root, _, files in os.walk(extract_dir):
        for f in files:
            if f.endswith('.apk'):
                return os.path.join(root, f)
    raise RuntimeError('bundletool produced no .apk')


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('input_aab')
    ap.add_argument('output_aab')
    ap.add_argument('sdk_dex_paths', nargs='+')
    ap.add_argument('api_key')
    ap.add_argument('--bundletool-jar', default=os.environ.get('BUNDLETOOL_JAR'))
    ap.add_argument('--updater-base-url', default=None,
                    help='Task 47c: https base URL of Zealot; adds the updater components to the manifest. '
                         'Pass "-" as api_key to inject the updater without the proxy SDK.')
    ap.add_argument('--store-name', default=None,
                    help='Task 47j: the tenant store display name the injected in-app update dialog shows. '
                         'Requires --updater-base-url. Absent means the first-party default.')
    ap.add_argument('--store-package', default=None,
                    help='Task 47j: the tenant store package the injected update dialog opens on Update. '
                         'Requires --updater-base-url. Absent means the first-party default.')
    ap.add_argument('--verify', action='store_true',
                     help='also run bundletool build-apks --mode=universal on the result (unsigned)')
    args = ap.parse_args()

    if not args.bundletool_jar:
        print('error: --bundletool-jar or $BUNDLETOOL_JAR must point at a bundletool-all-*.jar', file=sys.stderr)
        sys.exit(1)

    result = patch(args.input_aab, args.output_aab, args.sdk_dex_paths, args.api_key, args.bundletool_jar,
                   args.updater_base_url, args.store_name, args.store_package)
    print(f"[*] patched bundle written to {args.output_aab} (SDK dexes added: {result['dexes_added']})")

    if args.verify:
        apk_path = verify_with_bundletool(args.output_aab, args.bundletool_jar)
        print(f"[*] bundletool build-apks --mode=universal succeeded: {apk_path}")


if __name__ == '__main__':
    main()
