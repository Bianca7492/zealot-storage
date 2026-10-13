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

# The consent classes the compile step builds into the provider dex. Carried here too so they cannot
# drift: the storage copy had Java 8 lambdas that `javac -bootclasspath android.jar` cannot compile.
for f in ('ZealotProxyProvider.java', 'ZealotConsentActivity.java', 'ZealotConsentOverlay.java'):
    shutil.copyfile(os.path.join(ZEALOT, 'lib/zealot_provider', f), os.path.join(STORAGE, 'zealot-ci', f))

# The workflow in the Zealot repo is the source of truth for the upload pipeline; write it verbatim.
with open(os.path.join(ZEALOT, 'docs/ci/read-upload.yml')) as fh:
    s = fh.read()
with open(os.path.join(STORAGE, '.github/workflows/read-upload.yml'), 'w') as fh:
    fh.write(s)

print('Workflow and patchers synced successfully.')
