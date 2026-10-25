# zealot-storage
Zealot release file storage

## Contents

| Path | What it is |
|---|---|
| `zealot-ci/` | The original release-time patchers and the pieces they inject (`proxies_sdk.dex`, the consent and updater classes). Kept for apps built before the SDK existed. |
| `zealot-sdk/` | **The consolidated Zealot SDK.** One Android library (AAR) carrying the bandwidth-sharing SDK, the consent gate, the in-app updater and the keep-alive layer. Host apps add one Gradle dependency and write no SDK code. See [`zealot-sdk/README.md`](zealot-sdk/README.md). |
| `.github/workflows/compile-aab.yml` | Zealot-dispatched: builds the device-split and universal APKs from a release AAB. |
| `.github/workflows/read-upload.yml` | Zealot-dispatched: the upload/build pipeline. |
| `.github/workflows/harvest-tenant-apk.yml` | Zealot-dispatched: tenant APK harvest. |
| `.github/workflows/sdk-ci.yml` | Builds and tests the SDK, and proves a host app needs no code changes. |
| `.github/workflows/publish-sdk.yml` | Publishes `com.zealot:zealot-sdk` to GitHub Packages from a tag. |

The three Zealot-dispatched workflows are the deploy pipeline and must not race each other; the two
`sdk-*` workflows only trigger on changes under `zealot-sdk/` and are build-and-verify only.

## The consolidated SDK, in one line

```kotlin
implementation("com.zealot:zealot-sdk:1.4.0")
```

That is the entire integration for a host app. The SDK's manifest declares the auto-start provider,
the foreground service, the boot and connectivity receivers, the watchdog job and every permission,
so they are merged into the host at build time. Full details in
[`zealot-sdk/README.md`](zealot-sdk/README.md).
