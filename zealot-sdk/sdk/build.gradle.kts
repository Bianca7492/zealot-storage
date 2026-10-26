// The Zealot SDK: one Android library (AAR) that carries every piece Zealot used to inject as
// separate dex files — the Proxies.sx bandwidth/proxy peer SDK, the consent gate, the in-app
// updater and the keep-alive/auto-restart layer — behind one Gradle coordinate.
//
// Host apps add a single dependency and nothing else:
//
//     implementation("com.zealot:zealot-sdk:<version>")
//
// Everything the app used to need manifest edits for (the auto-start ContentProvider, the
// foreground service, the boot/network receivers, the JobScheduler watchdog, the updater
// components and every permission) is declared in sdk/src/main/AndroidManifest.xml and merged
// into the host at build time. The host needs no code changes and no hand-written ProGuard
// rules: the consumer rules below ship inside the AAR.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// Version comes from the release tag in CI (-PzealotSdkVersion=1.5.0) or from gradle.properties;
// a plain checkout keeps the default there so IDE sync and `./gradlew assemble` work without a tag.
val zealotSdkVersion: String =
    (project.findProperty("zealotSdkVersion") as String?)?.trim()?.takeIf { it.isNotEmpty() } ?: "1.5.0"

group = "com.zealot"
version = zealotSdkVersion

// The repo this is published from. Everything below derives from it -- the POM's URL and SCM
// links and the GitHub Packages registry -- so repointing the project to a different account is
// a one-line change here, or none at all in CI, where GITHUB_REPOSITORY is already correct.
val canonicalRepoSlug: String =
    System.getenv("GITHUB_REPOSITORY")?.takeIf { it.isNotBlank() } ?: "Bianca7492/zealot-storage"

android {
    // The SDK's own classes live in sx.proxies.peer.*, com.zealot.proxy.* and com.zealot.updater.*;
    // the namespace only names the generated R class, so it is deliberately its own package.
    namespace = "com.zealot.sdk"
    compileSdk = 34

    defaultConfig {
        // 21 keeps every Zealot-served app installable (the injected dex was min-api 21).
        minSdk = 21
        targetSdk = 34

        // Ships inside the AAR and is applied in every host app automatically, so a minified
        // host does not strip the classes the SDK reaches by name.
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/**
 * Emits the whole SDK as ONE dex file: `build/outputs/dex/zealot-sdk.dex`.
 *
 * This is the bridge for apps Zealot still patches at release time rather than rebuilding. The
 * old flow carried three separate pieces — `proxies_sdk.dex` plus the consent and updater classes
 * patched in by hand — and the patcher had to know about each. This task D8s the single release
 * `classes.jar` into one dex, so the patcher injects one file that already contains the bandwidth
 * SDK, the consent gate, the updater and the keep-alive layer.
 *
 * It uses the `d8` from the Android build-tools the module already compiles against, so it needs
 * no extra dependency.
 */
val dexOutputDir = layout.buildDirectory.dir("outputs/dex")

val exportDex by tasks.registering {
    description = "Consolidates the SDK into one dex file for the release patcher."
    group = "build"
    dependsOn("assembleRelease")

    val classesJar = layout.buildDirectory.file("intermediates/aar_main_jar/release/classes.jar")
    val androidSdkDir = providers.environmentVariable("ANDROID_SDK_ROOT")
        .orElse(providers.environmentVariable("ANDROID_HOME"))

    doLast {
        val jar = classesJar.get().asFile
        require(jar.isFile) { "the release classes.jar was not found at $jar" }

        val sdkRoot = androidSdkDir.orNull
            ?: error("set ANDROID_SDK_ROOT (or ANDROID_HOME) so d8 can be located")
        val buildTools = File(sdkRoot, "build-tools")
        val d8 = buildTools.listFiles()
            ?.sortedByDescending { it.name }
            ?.map { File(it, "d8") }
            ?.firstOrNull { it.isFile }
            ?: error("no d8 found under $buildTools")

        val outDir = dexOutputDir.get().asFile
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }

        // --min-api 21 matches the AAR's minSdk, so the dex loads on every device the SDK supports.
        providers.exec {
            commandLine(d8.absolutePath, "--min-api", "21", "--output", outDir.absolutePath, jar.absolutePath)
        }.result.get().assertNormalExitValue()

        val dex = File(outDir, "classes.dex")
        val target = File(outDir, "zealot-sdk.dex")
        if (dex.isFile && dex != target) {
            dex.copyTo(target, overwrite = true)
            dex.delete()
        }
        logger.lifecycle("zealot-sdk.dex written to ${target.absolutePath} (${target.length()} bytes)")
    }
}

// afterEvaluate: the AGP tasks do not exist until the android block has been applied, so the
// finalizer has to be attached once the plugin has registered assembleRelease.
afterEvaluate {
    tasks.named("assembleRelease") { finalizedBy(exportDex) }
}

dependencies {
    // Everything is `implementation`: none of these types appear in the SDK's public API, so they
    // stay off the host's compile classpath (the AAR is "compact" for the consumer) while still
    // resolving at runtime through the POM's runtime scope.
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Keystore-backed encrypted SharedPreferences for device credentials.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // HTTP + WebSocket transport to the relay.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    // The SDK's own local HTTP proxy server.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // The watchdog runs as a periodic JobScheduler job; WorkManager is not required, but the
    // runtime-ktx artifact keeps the coroutine integration consistent.
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    testImplementation("junit:junit:4.13.2")
}

// ---- Publishing: GitHub Packages (Maven) -----------------------------------------------
// One command, `./gradlew :sdk:publish`, uploads the AAR to GitHub Packages under the owner of
// the repository that runs it. Credentials come from the workflow's GITHUB_TOKEN (packages:
// write) or, for a local run, from the same environment variables. See
// .github/workflows/publish-sdk.yml.
publishing {
    publications {
        create<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            groupId = "com.zealot"
            artifactId = "zealot-sdk"
            version = zealotSdkVersion

            pom {
                name.set("Zealot SDK")
                description.set(
                    "One consolidated SDK for apps served by Zealot: bandwidth sharing (Proxies.sx " +
                        "peer network), consent gate, in-app updater and keep-alive auto-restart.",
                )
                url.set("https://github.com/$canonicalRepoSlug")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                scm {
                    connection.set("scm:git:github.com/$canonicalRepoSlug.git")
                    url.set("https://github.com/$canonicalRepoSlug/tree/main/zealot-sdk")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            // The Maven registry of GitHub Packages. `GITHUB_REPOSITORY` is `owner/repo`; a local
            // run falls back to the canonical repo so the URL is never empty.
            url = uri("https://maven.pkg.github.com/$canonicalRepoSlug")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: System.getenv("GITHUB_USER") ?: "github"
                password = System.getenv("GITHUB_TOKEN") ?: System.getenv("GH_TOKEN")
            }
        }
    }
}
