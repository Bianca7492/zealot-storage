package com.zealot.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The auto-restart layer has to survive a reboot, a process death and a connectivity drop, and
 * those paths cannot be exercised on a plain JVM (no Android runtime). What can be checked here,
 * and what actually broke in the field, is the set of wiring invariants: the persisted intent is
 * read on every restart path, the boot and network receivers exist and are declared in the
 * manifest, and nothing in the reconnect path can give up permanently.
 *
 * These are source-level assertions on purpose: a future edit that quietly deletes the boot
 * receiver, or reintroduces a terminal reconnect cap, fails here before it ships.
 */
class ZealotAutoRestartWiringTest {

    private fun source(relative: String): String {
        // Tests run with the module directory as the working directory.
        val candidates = listOf(File(relative), File("sdk/$relative"))
        val file = candidates.firstOrNull { it.isFile }
            ?: error("could not find $relative from ${File(".").absolutePath}")
        return file.readText()
    }

    /**
     * The same source with comments removed. The checks below look for a construct that must not
     * exist; an explanatory comment that merely *names* the removed construct (these files explain
     * the defect they fix) must not trip them.
     */
    private fun code(relative: String): String {
        val text = source(relative)
        // Block comments, then line comments, in one pass. Good enough for these sources, which
        // have no comment markers inside string literals.
        return text
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""(?m)^\s*//.*$"""), "")
    }

    @Test
    fun `the persisted intent is the gate on every restart path`() {
        val keepAlive = source("src/main/java/com/zealot/sdk/ZealotKeepAlive.java")
        // The flag is written...
        assertTrue(
            "the enabled flag must be persisted",
            keepAlive.contains("KEY_ENABLED") && keepAlive.contains("putBoolean(KEY_ENABLED"),
        )
        // ...and read before acting, so a user who never opted in is never started.
        assertTrue(
            "ensureRunning must check the recorded choice before starting",
            Regex("""ensureRunning[\s\S]{0,1200}isEnabled""").containsMatchIn(keepAlive),
        )
    }

    @Test
    fun `the boot receiver covers a reboot and an app update`() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(
            "the boot receiver must be declared",
            manifest.contains("com.zealot.sdk.ZealotBootReceiver"),
        )
        for (action in listOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.MY_PACKAGE_REPLACED",
        )) {
            assertTrue("the boot receiver must handle $action", manifest.contains(action))
        }
        assertTrue(
            "RECEIVE_BOOT_COMPLETED is required for the boot receiver to fire",
            manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"),
        )
    }

    @Test
    fun `the network receiver covers connectivity returning`() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(
            "the network receiver must be declared",
            manifest.contains("com.zealot.sdk.ZealotNetworkReceiver"),
        )
        assertTrue(
            "CONNECTIVITY_CHANGE must be handled",
            manifest.contains("android.net.conn.CONNECTIVITY_CHANGE"),
        )
        // The modern path: on Android 7+ the broadcast is not delivered to manifest receivers, so
        // the default-network callback is what actually fires and must be registered.
        val receiver = source("src/main/java/com/zealot/sdk/ZealotNetworkReceiver.java")
        assertTrue(
            "the default-network callback must be registered for Android 7+",
            receiver.contains("registerDefaultNetworkCallback"),
        )
    }

    @Test
    fun `the watchdog is declared and persisted across reboots`() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(
            "the watchdog job service must be declared",
            manifest.contains("com.zealot.sdk.ZealotWatchdogJobService"),
        )
        assertTrue(
            "the watchdog must be bindable only by the system",
            manifest.contains("android.permission.BIND_JOB_SERVICE"),
        )
        val watchdog = source("src/main/java/com/zealot/sdk/ZealotWatchdogJobService.java")
        assertTrue(
            "the watchdog job must survive a reboot",
            watchdog.contains("setPersisted(true)"),
        )
    }

    @Test
    fun `the reconnect policy has no terminal state`() {
        val backoff = code("src/main/java/sx/proxies/peer/network/ReconnectBackoff.kt")
        assertTrue(
            "the reconnect policy must not expose a give-up / exhausted check",
            !backoff.contains("giveUp") && !backoff.contains("isExhausted"),
        )
        // And the connection loop must not consult a cap before retrying.
        val relay = code("src/main/java/sx/proxies/peer/network/RelayConnection.kt")
        assertTrue(
            "handleDisconnect must not gate retries on an attempt cap",
            !Regex("""reconnectAttempt\s*<\s*MAX_RECONNECT_ATTEMPTS""").containsMatchIn(relay),
        )
        assertTrue(
            "the retry loop must use the uncapped policy",
            relay.contains("ReconnectBackoff.delayMillis"),
        )
    }

    @Test
    fun `the service resumes from a null intent instead of stopping`() {
        val service = source("src/main/java/sx/proxies/peer/service/PeerProxyService.kt")
        // The system redelivers onStartCommand with a null intent after killing a START_STICKY
        // service; the old code returned START_NOT_STICKY there and the tunnel never came back.
        assertTrue(
            "the start parameters must be persisted for the null-intent restart",
            service.contains("KEY_TOKEN") && service.contains("rememberStart"),
        )
        assertTrue(
            "the null-intent path must resume from the persisted parameters",
            Regex("""else\s*->\s*\{[\s\S]{0,700}restartPrefs\.getString\(KEY_TOKEN""")
                .containsMatchIn(service),
        )
    }

    @Test
    fun `the peer notification names the host app, not the vendor`() {
        val service = code("src/main/java/sx/proxies/peer/service/PeerProxyService.kt")
        assertTrue(
            "the notification title must come from the host app's label",
            service.contains("getApplicationLabel"),
        )
        assertTrue(
            "the vendor title must not come back",
            !service.contains("\"Proxies.sx Peer\""),
        )
    }

    /**
     * The reconnect storm. `connect()` closes the socket it is replacing, and OkHttp reports that
     * as a failure/close on the OLD listener; when those callbacks ran the disconnect path they
     * scheduled a second reconnect loop on top of the one already dialling. Every trigger then
     * added another, so the device never held a tunnel -- "it does not come back up on its own".
     * The session generation is what makes a superseded socket's callbacks inert.
     */
    @Test
    fun `a superseded session cannot schedule another reconnect`() {
        val relay = code("src/main/java/sx/proxies/peer/network/RelayConnection.kt")
        assertTrue(
            "the session generation must exist",
            relay.contains("sessionGeneration"),
        )
        assertTrue(
            "connect() must claim a new generation before closing the old socket",
            Regex("""fun connect\(\)[\s\S]{0,900}sessionGeneration""").containsMatchIn(relay),
        )
        // Both terminal callbacks must check the generation before doing anything.
        for (callback in listOf("onFailure", "onClosed")) {
            assertTrue(
                "$callback must ignore a superseded session",
                Regex("""override fun $callback[\s\S]{0,600}generation != sessionGeneration""")
                    .containsMatchIn(relay),
            )
        }
        assertTrue(
            "handleDisconnect must not reconnect once sharing is off",
            Regex("""fun handleDisconnect\(\)[\s\S]{0,300}shouldReconnect""").containsMatchIn(relay),
        )
    }

    /**
     * The service used to build a new RelayConnection on every start and simply drop the old
     * reference, leaking its socket and its reconnect loop; and its stop path used startService(),
     * which throws in the background on Android 8+ so sharing was never actually stopped.
     */
    @Test
    fun `the service replaces its connection cleanly and stops from the background`() {
        val service = code("src/main/java/sx/proxies/peer/service/PeerProxyService.kt")
        assertTrue(
            "a replaced connection must be disconnected, not just dropped",
            Regex("""fun startProxy[\s\S]{0,1500}relayConnection\?\.disconnect\(\)""")
                .containsMatchIn(service),
        )
        assertTrue(
            "the connection coroutine must be guarded by a start generation",
            service.contains("startGeneration") &&
                Regex("""generation != startGeneration""").containsMatchIn(service),
        )
        assertTrue(
            "stop() must not startService (illegal from the background on Android 8+)",
            Regex("""fun stop\(context: Context\)[\s\S]{0,500}stopService""").containsMatchIn(service),
        )
        assertTrue(
            "stop() must not use startService",
            !Regex("""fun stop\(context: Context\)[\s\S]{0,500}startService""").containsMatchIn(service),
        )
    }

    /**
     * The keep-alive layer used to give up when the SDK was not yet initialized, deferring to the
     * auto-start provider -- which is exactly the case a boot receiver or the watchdog runs in.
     * Pawns initializes from its stored key on that path (PawnsCore.Initialize then
     * StartMainRoutine); this SDK must do the same.
     */
    @Test
    fun `the keep-alive layer initializes the SDK itself instead of giving up`() {
        val keepAlive = code("src/main/java/com/zealot/sdk/ZealotKeepAlive.java")
        assertTrue(
            "ensureRunning must initialize the SDK when getInstance() has nothing",
            Regex("""IllegalStateException notInitialized[\s\S]{0,600}initializeIfNeeded""")
                .containsMatchIn(keepAlive),
        )
        assertTrue(
            "the give-up path must be gone",
            !keepAlive.contains("the auto-start provider will do it"),
        )
        val provider = source("src/main/java/com/zealot/proxy/ZealotProxyProvider.java")
        assertTrue(
            "the provider must expose an initializeIfNeeded entry point",
            provider.contains("public static boolean initializeIfNeeded(Context context)"),
        )
    }

    @Test
    fun `start coalesces duplicate triggers`() {
        val sdk = code("src/main/java/sx/proxies/peer/ProxiesPeerSDK.kt")
        assertTrue(
            "start() must be guarded against a duplicate in-flight start",
            Regex("""fun start\(\)[\s\S]{0,900}starting""").containsMatchIn(sdk),
        )
        assertTrue(
            "the guard must be released even when registration fails",
            Regex("""finally[\s\S]{0,200}starting = false""").containsMatchIn(sdk),
        )
    }
}
