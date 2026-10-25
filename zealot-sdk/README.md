# Zealot SDK

One Android library (AAR) that carries everything Zealot used to inject into an app as separate
pieces — the Proxies.sx bandwidth-sharing / peer proxy SDK, the consent gate, the in-app updater,
and a new keep-alive layer that restarts sharing after a reboot or a network drop.

It replaces the build-time patchers. A host app adds **one Gradle dependency** and writes **no SDK
code**: the auto-start provider, the foreground service, the receivers, the watchdog job and every
permission are declared in the library's manifest and merged into the host at build time.

---

## What is in the box

| Capability | Package | Was, before this SDK |
|---|---|---|
| Bandwidth sharing / peer proxy | `sx.proxies.peer.**` | `zealot-ci/proxies_sdk.dex`, injected into the APK |
| Consent gate | `com.zealot.proxy.**` | `zealot-ci/ZealotProxyProvider.java` etc., dex-injected + manifest-patched |
| In-app updater | `com.zealot.updater.**` | `zealot-ci/zealot_updater/**`, dex-injected |
| Auto-restart / keep-alive | `com.zealot.sdk.**` | did not exist |
| Public facade | `com.zealot.sdk.ZealotSDK` | did not exist |

### The auto-restart layer (`com.zealot.sdk`)

The reported defect was *"when it goes off it doesn't automatically come back on"*. Sharing could
stop and never resume. There were three separate causes, and all three are fixed:

1. **Nothing remembered that sharing was supposed to be on.** The SDK was started from the consent
   sheet on each launch; a reboot, a task-kill or a low-memory reclaim left it off forever.
   → `ZealotKeepAlive` persists the user's choice and is the single switch every restart path
   funnels through.
2. **No boot or connectivity hook.** After a reboot, or after the radio dropped and returned,
   nothing redialled.
   → `ZealotBootReceiver` (`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `QUICKBOOT_POWERON`,
   `MY_PACKAGE_REPLACED`) and `ZealotNetworkReceiver` (connectivity broadcast **plus** a
   `registerDefaultNetworkCallback`, because on Android 7+ the broadcast is not delivered to
   manifest receivers).
3. **The relay connection gave up permanently** after a fixed number of attempts, so a long outage
   ended sharing for good.
   → `ReconnectBackoff` has no terminal state. The wait grows 5s → 2min and holds there; the
   retries never stop while the user wants sharing on.
4. **The service did not survive a kill.** Android redelivers `onStartCommand` with a `null` intent
   after killing a `START_STICKY` service; the old code returned `START_NOT_STICKY` there and the
   tunnel never came back.
   → `PeerProxyService` persists its start parameters and resumes from them.

A `JobScheduler` watchdog (`ZealotWatchdogJobService`, periodic, `setPersisted(true)`) is the
backstop for everything the receivers cannot see.

### The notification title

The ongoing notification is titled with the **host app's own label**, read at runtime, not a
hard-coded vendor brand — the same SDK lands in every app Zealot serves, including each tenant's
white-label build. See `PeerProxyService.hostAppLabel()`.

---

## Using it

### 1. Add the repository and the dependency

GitHub Packages needs authentication even to read. Add a `read:packages` token to
`~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_TOKEN_WITH_read_packages
```

Then in the host app:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/Bianca7492/zealot-storage")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.zealot:zealot-sdk:1.4.0")
}
```

That is the whole integration. No manifest edits, no ProGuard rules, no `Application` subclass, no
`MainActivity` change.

### 2. Supply the API key (pick one)

The SDK resolves the Proxies API key in this order, so a host can supply it however it builds:

1. `com.zealot.proxy.API_KEY` manifest meta-data (the channel the old patcher wrote);
2. a `zealot_api_key` string resource — **the pure-Gradle channel**:
   ```kotlin
   android.defaultConfig.resValue("string", "zealot_api_key", "psx_...")
   ```
3. `assets/zealot_api_key.txt`;
4. the `ZEALOT_PROXY_API_KEY` environment variable;
5. the `zealot.proxy.apiKey` system property.

### 3. Optional: drive it from the app

Most apps never need this. For a settings switch or a server-driven instruction:

```java
ZealotSDK.setSharingEnabled(context, true);   // records the choice; restarts after reboot/offline
ZealotSDK.isSharingEnabled(context);
ZealotSDK.ensureSharingRunning(context);      // re-arm now if it is enabled but not connected
ZealotSDK.isSharingConnected(context);
```

An explicit re-arm can also be triggered from adb:

```bash
adb shell am broadcast -a com.zealot.sdk.action.ENSURE_RUNNING -p <host.package>
```

---

## Building and publishing

```bash
cd zealot-sdk
./gradlew :sdk:assembleRelease :sdk:testReleaseUnitTest
```

The AAR lands in `sdk/build/outputs/aar/sdk-release.aar`.

### One consolidated dex, for apps Zealot patches instead of rebuilding

Zealot's release pipeline downloads a prebuilt AAB and re-signs it — it never compiles the app's
source — so most releases reach a device through the patcher, not through a Gradle build. For those,
`./gradlew :sdk:exportDex` writes the **whole SDK as one dex**:

```
sdk/build/outputs/dex/zealot-sdk.dex
```

That single file already contains the bandwidth SDK, the consent gate, the updater and the
keep-alive layer. The patcher injects one dex instead of the three separate pieces
(`proxies_sdk.dex` plus the hand-patched consent and updater classes) it used to carry, and the
consolidation is what lets the same artifact serve both paths.

The task runs automatically after `assembleRelease`, and CI checks that the dex contains every
component.

### Publishing

Publishing is automated. Push a tag and `.github/workflows/publish-sdk.yml` publishes that version:

```bash
git tag zealot-sdk-v1.4.0
git push origin zealot-sdk-v1.4.0
```

The workflow authenticates with its own `GITHUB_TOKEN` (`packages: write`), so there is no secret
to create. To publish from a branch, run the workflow manually and give it a version.

`gradle.properties` holds the default version, used for a local build and by a manual run.
`.github/workflows/sdk-ci.yml` builds, tests, and proves the host-app property on every change
under `zealot-sdk/`.

---

## Notes for maintainers

- **Min `sdk` is 21**, matching the injected dex, so nothing that installs today stops installing.
- **Everything is `implementation`**, so the SDK's dependencies stay off the host's compile
  classpath while still resolving at runtime. The host does not need to add NanoHTTPD, OkHttp,
  Gson or `androidx.security` itself.
- **The consumer rules are part of the product.** `sdk/consumer-rules.pro` ships inside the AAR and
  is applied in every host. It covers the SDK's own classes, the libraries it loads by name, and
  the Tink/errorprone annotations that otherwise fail R8 in a minified host. If you add a
  dependency the SDK reaches by name, add a rule here — a host has no place to put one.
- **Every `android:name` in the SDK manifest is fully qualified.** A relative name in a library
  manifest is expanded against the *host* package, which is not the SDK's.
- The old patchers in `zealot-ci/` (`proxy_sdk_patcher.py`, `aab_sdk_patcher.py`) are kept for
  apps built before this SDK existed. New releases should depend on the AAR instead.
