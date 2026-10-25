# AGENTS.md

Repository-specific knowledge for agents working in `zealot-storage`. Read this before changing
anything under `zealot-sdk/` or `zealot-ci/`.

## What this repo is

The release-engineering side of Zealot: the CI that builds/signs/patches app releases, and the
Zealot SDK itself. The Zealot Rails app (a separate repo) dispatches the workflows here.

## Hard rules

- **Never add a workflow that races the deploy pipeline.** `compile-aab.yml`, `read-upload.yml`
  and `harvest-tenant-apk.yml` are dispatched by Zealot and must not be triggered by pushes. New
  workflows must be scoped to their own path (see `sdk-ci.yml`, which triggers only on
  `zealot-sdk/**`).
- **The patchers and the SDK must stay in sync.** Two delivery paths exist for the same code:
  1. a host app depending on `com.zealot:zealot-sdk` (the preferred path), and
  2. the release-time patchers in `zealot-ci/`, for apps Zealot downloads prebuilt and re-signs
     (the pipeline never compiles app source, so most releases take this path).
  Adding an SDK component means updating both: the library manifest *and* the two patcher
  manifest edits (`ManifestPatch.java` for AAB, `proxy_sdk_patcher.py` for APK).
- **Patcher manifest edits must be conditional on the injected dex.** A component is declared only
  if the dex carries the class (`sdkDexHas` in Java, a byte substring test in Python). This is what
  lets an old dex keep producing the old manifest. Do not add an unconditional entry.
- **Every `android:` attribute in `ManifestPatch.java` needs a framework resource id** in `resId()`,
  read from `android.jar` (`javap -constants android/R$attr.class`), not from memory — a wrong id
  silently makes the attribute unread and the manifest malformed. `priority` is `0x0101001C`, which
  is easy to get wrong.
- **`android:name` in a library manifest must be fully qualified.** A relative name resolves
  against the *host* package, not the SDK's.

## Environment (this sandbox)

```bash
export JAVA_HOME=/tmp/jdk/jdk-17.0.21+9
export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_SDK_ROOT=/tmp/android-sdk
export ANDROID_HOME=/tmp/android-sdk
```

Android SDK at `/tmp/android-sdk` (build-tools 34.0.0, platform android-34). No `unzip`; use
`python3 -m zipfile` or `zipfile` in a script. `apktool` is not installed — download
`apktool_2.9.3.jar` and wrap it in a shell script on `PATH` if you need the APK patcher path.

## Build and test

```bash
cd zealot-sdk
./gradlew :sdk:assembleRelease :sdk:testReleaseUnitTest :sdk:exportDex
```

- AAR: `sdk/build/outputs/aar/sdk-release.aar`
- Consolidated dex (what the patchers inject): `sdk/build/outputs/dex/zealot-sdk.dex`
- Unit tests: `sdk/build/test-results/testReleaseUnitTest` (18 tests)

The suite is source-level wiring assertions (`ZealotAutoRestartWiringTest`) plus two pure-JVM
tests (`ReconnectBackoffTest`, `ReconnectStormModelTest`). They exist because the field defect —
"bandwidth does not come back once the device goes offline" — is a set of ordering/wiring bugs that
a plain JVM cannot exercise directly. `ReconnectStormModelTest` models `RelayConnection`'s control
flow to show why a superseded socket's callbacks must not schedule another reconnect; keep it in
step with `connect()`/`handleDisconnect()` if you touch them.

`exportDex` runs `d8` from `$ANDROID_SDK_ROOT/build-tools/*/d8`, so that env var must be set.

## Verifying the two properties that matter

Both are cheap and both have caught real bugs:

1. **Zero host-app code.** Build a host app whose only SDK reference is the Gradle dependency and
   which is `isMinifyEnabled = true`, then check the APK's dex for the SDK classes. `sdk-ci.yml`
   does this. R8 will fail on `androidx.security` → Tink unless `consumer-rules.pro` covers the
   errorprone annotations — that is why those `-dontwarn` lines are there.
2. **The repo's own gates.** Run them against the freshly built dex:
   ```bash
   python3 zealot-ci/check-peer-notification-title.py zealot-sdk/sdk/build/outputs/dex/zealot-sdk.dex
   python3 zealot-ci/scan_bandwidth_sdks.py <host.apk> zealot-ci/bandwidth_sdk_fingerprints.yml
   ```
   `check-peer-notification-title.py` requires `getApplicationLabel` to be called *inside*
   `createNotification`. Extracting it into a helper makes the gate fail — keep the call inline.

## Testing a patcher change

`aab_sdk_patcher.py` needs `bundletool-all-1.18.3.jar` (CI pins 1.18.3; older jars do not bundle
the `com.android.aapt.Resources` classes it compiles against) and `ManifestPatch` compiles to
`zealot-ci/aab_manifest_patch/build/` (gitignored).

```bash
export BUNDLETOOL_JAR=/tmp/bundletool-all-1.18.3.jar
python3 zealot-ci/aab_sdk_patcher.py in.aab out.aab zealot-sdk/sdk/build/outputs/dex/zealot-sdk.dex \
  "psx_KEY" --updater-base-url "https://zealot.example" --verify
```

`--verify` runs `bundletool build-apks` on the result, which is the real proof the manifest edit
survived aapt2's protobuf → binary conversion. A `ManifestPatch dump` on the patched manifest
shows whether each component landed. The APK patcher path also needs `uber-apk-signer` for its
final signing step; without it the patch still completes and the intermediate APK is inspectable.

## Publishing

Tag `zealot-sdk-v<version>` and push; `publish-sdk.yml` publishes to GitHub Packages with its own
`GITHUB_TOKEN`. The version default lives in `zealot-sdk/gradle.properties`
(`zealotSdkVersion`), overridable with `-PzealotSdkVersion=`.

## Repository home and the account migration

This project moved from the `christiana-angel` account (suspended by GitHub, so the repo and its
Maven registry are unreachable) to **`Bianca7492`**. Everything self-referential now derives from
one value:

- `canonicalRepoSlug` in `zealot-sdk/sdk/build.gradle.kts` — feeds the POM's `url`, the `scm`
  links and the GitHub Packages registry URL.
- In CI nothing needs changing: `GITHUB_REPOSITORY` is already `owner/repo`, and the code prefers
  it over the fallback.

To repoint again, change that one fallback and the README's Gradle snippet.
`harvest-tenant-apk.yml` names `Bianca7492/Storeapp` in three places — a *different* repo (the
tenant app source that dispatches the bundle), not this one. It was repointed in the same move;
keep the two in step, because the workflow's ownership check fails loudly if they drift apart.

The sibling repos were migrated the same way — `zealot`, `Storeapp`, `D-Store`,
`appstore-metadata`, `distr` — all now under `Bianca7492` as private repos. They arrived as
shallow single-commit clones and were each rewritten into a real root commit (a shallow clone
cannot be pushed: git refuses with "shallow update not allowed"), so their history is a single
commit, not the upstream history.

`bolivian-peru/android-peer-sdk` is a **separate, live** upstream and is not part of this
account; do not migrate or repoint it.
