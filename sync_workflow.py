import os
import shutil

ZEALOT = os.path.expanduser('~/zealot')
STORAGE = os.path.expanduser('~/zealot-storage')
if not os.path.isdir(ZEALOT):
    ZEALOT = '/workspace/zealot'
if not os.path.isdir(STORAGE):
    STORAGE = '/workspace/zealot-storage'

os.makedirs(os.path.join(STORAGE, '.github/workflows'), exist_ok=True)
os.makedirs(os.path.join(STORAGE, 'zealot-ci'), exist_ok=True)

# Copy the Python patchers verbatim from the Zealot repo. proxy_sdk_patcher.py was missing from the
# storage repo entirely, so the APK inject step (which lists it as a required file) always failed.
for f in ('proxy_sdk_patcher.py', 'aab_sdk_patcher.py'):
    shutil.copyfile(os.path.join(ZEALOT, 'lib', f), os.path.join(STORAGE, 'zealot-ci', f))

# Task 47c/47j: the manifest patcher is compiled fresh from the storage copy every run, so it must be
# mirrored here too. The updater's Java sources used to be mirrored as well, but they now ship inside the
# SDK (zealot-sdk/sdk/src/main/java/com/zealot/updater) and are compiled into the consolidated dex by the
# SDK's own :sdk:exportDex task, so there is no zealot_updater/ directory to copy any more.
shutil.copytree(os.path.join(ZEALOT, 'lib/aab_manifest_patch'),
                os.path.join(STORAGE, 'zealot-ci/aab_manifest_patch'),
                dirs_exist_ok=True)

# The workflow in the Zealot repo is the source of truth for the upload pipeline; write it verbatim.
with open(os.path.join(ZEALOT, 'docs/ci/read-upload.yml')) as fh:
    s = fh.read()
with open(os.path.join(STORAGE, '.github/workflows/read-upload.yml'), 'w') as fh:
    fh.write(s)

print('Workflow and patchers synced successfully.')
