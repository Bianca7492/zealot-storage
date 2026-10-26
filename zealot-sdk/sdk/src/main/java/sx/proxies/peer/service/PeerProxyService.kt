package sx.proxies.peer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import sx.proxies.peer.ProxiesPeerSDK
import sx.proxies.peer.network.RelayConnection
import sx.proxies.peer.network.LocalProxyServer

class PeerProxyService : Service() {
    companion object {
        private const val TAG = "PeerProxyService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "peer_proxy_channel"

        const val ACTION_START = "sx.proxies.peer.action.START"
        const val ACTION_STOP = "sx.proxies.peer.action.STOP"
        const val EXTRA_DEVICE_TOKEN = "device_token"
        const val EXTRA_RELAY_URL = "relay_url"
        const val EXTRA_RELAY_PINNED = "relay_pinned"

        // Keys for the persisted start parameters (see onStartCommand's null-intent path).
        private const val KEY_TOKEN = "start_token"
        private const val KEY_RELAY = "start_relay"
        private const val KEY_RELAY_PINNED = "start_relay_pinned"

        // @JvmStatic so Java callers (the keep-alive layer, the SDK facade) can call these as
        // PeerProxyService.start(...) / .stop(...) rather than through Companion.
        @JvmStatic
        fun start(context: Context, deviceToken: String, relayUrl: String, relayPinned: Boolean = false) {
            val intent = Intent(context, PeerProxyService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_TOKEN, deviceToken)
                putExtra(EXTRA_RELAY_URL, relayUrl)
                putExtra(EXTRA_RELAY_PINNED, relayPinned)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            // stopService, not startService-with-ACTION_STOP. Every restart path funnels through
            // here (ZealotKeepAlive.setEnabled(false), the notification's Stop action), and both
            // of those can run while the app is in the background -- where startService() throws
            // IllegalStateException on Android 8+ and the service was never actually stopped.
            // onDestroy() does the teardown.
            try {
                context.stopService(Intent(context, PeerProxyService::class.java))
            } catch (t: Throwable) {
                Log.e(TAG, "stopService failed", t)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var relayConnection: RelayConnection? = null
    private var localProxyServer: LocalProxyServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Bumped by every [startProxy] and by [stopProxy]. The connection coroutine captures it before
     * it does any work and refuses to publish a connection if a newer start (or a stop) has since
     * taken over -- so a slow DNS lookup cannot leave an orphaned socket dialling the relay after
     * the service has already moved on to a newer one.
     */
    @Volatile private var startGeneration = 0

    // Last START parameters, persisted so the service can restart itself. When Android kills a
    // service that returned START_STICKY it re-delivers onStartCommand with a NULL intent; the
    // old code fell through to START_NOT_STICKY in that case and the tunnel never came back —
    // this is the "when it goes off it doesn't automatically come back on" defect at the service
    // level. Persisting the parameters lets a null-intent redelivery resume exactly as before.
    private val restartPrefs by lazy {
        getSharedPreferences("peer_proxy_service", Context.MODE_PRIVATE)
    }

    private var totalBytesIn = 0L
    private var totalBytesOut = 0L
    private var isConnected = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val token = intent.getStringExtra(EXTRA_DEVICE_TOKEN) ?: return START_NOT_STICKY
                val relayUrl = intent.getStringExtra(EXTRA_RELAY_URL) ?: return START_NOT_STICKY
                val relayPinned = intent.getBooleanExtra(EXTRA_RELAY_PINNED, false)
                rememberStart(token, relayUrl, relayPinned)
                startProxy(token, relayUrl, relayPinned)
                return START_STICKY
            }
            ACTION_STOP -> {
                forgetStart()
                stopProxy()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // A null intent means Android restarted the service after killing it (the
                // START_STICKY redelivery). Resume from the persisted parameters if there are
                // any; otherwise there is genuinely nothing to run.
                val token = restartPrefs.getString(KEY_TOKEN, null)
                val relayUrl = restartPrefs.getString(KEY_RELAY, null)
                if (token != null && relayUrl != null) {
                    val relayPinned = restartPrefs.getBoolean(KEY_RELAY_PINNED, false)
                    Log.i(TAG, "Restarted by the system; resuming the tunnel")
                    startProxy(token, relayUrl, relayPinned)
                    return START_STICKY
                }
                return START_NOT_STICKY
            }
        }
    }

    private fun rememberStart(token: String, relayUrl: String, relayPinned: Boolean) {
        restartPrefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_RELAY, relayUrl)
            .putBoolean(KEY_RELAY_PINNED, relayPinned)
            .apply()
    }

    private fun forgetStart() {
        restartPrefs.edit().clear().apply()
    }

    private fun startProxy(token: String, relayUrl: String, relayPinned: Boolean) {
        Log.d(TAG, "Starting proxy service")

        // This start supersedes any earlier one still in flight.
        val generation = ++startGeneration

        // A restart trigger (the watchdog, the network receiver, ensureRunning) can land here
        // while a previous connection is still live. Replacing relayConnection without closing the
        // old one leaked its WebSocket and its reconnect loop: the old object kept redialling the
        // relay in the background, so the device opened more and more sessions it never used. Tear
        // the previous one down first; disconnect() also stops its reconnect loop.
        try {
            relayConnection?.disconnect()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to close the previous relay connection", t)
        }
        relayConnection = null
        localProxyServer?.stop()
        localProxyServer = null

        // Start foreground immediately
        startForeground(NOTIFICATION_ID, createNotification("Connecting..."))

        serviceScope.launch {
            try {
                // Built into a local and published only while this start is still the current one.
                // Reading the mutable field here instead would let a second startProxy() swap it out
                // from under this coroutine, so both would connect the SAME RelayConnection and open
                // two sockets -- the same storm the generation guard prevents inside it.
                val connection = RelayConnection(
                    context = applicationContext,
                    relayUrl = relayUrl,
                    relayPinned = relayPinned,
                    token = token,
                    onConnected = { deviceId ->
                        isConnected = true
                        updateNotification("Connected as $deviceId")
                        // Until 1.3.2 nothing ever moved the SDK off CONNECTING, so every
                        // integrator UI showed "Connecting" while the device was serving.
                        runCatching { ProxiesPeerSDK.getInstance().updateStatus(ProxiesPeerSDK.Status.CONNECTED) }
                        // Start local proxy server
                        startLocalProxy()
                    },
                    onDisconnected = {
                        isConnected = false
                        updateNotification("Disconnected - reconnecting")
                        runCatching { ProxiesPeerSDK.getInstance().updateStatus(ProxiesPeerSDK.Status.CONNECTING) }
                    },
                    onProxyRequest = { request ->
                        handleProxyRequest(request)
                    },
                    onTrafficUpdate = { bytesIn, bytesOut ->
                        totalBytesIn += bytesIn
                        totalBytesOut += bytesOut
                        updateNotificationStats()
                    }
                )
                if (generation != startGeneration) {
                    // A newer start (or a stop) took over while this one was being built. Drop it
                    // instead of connecting a second tunnel nobody will close.
                    Log.d(TAG, "Discarding a superseded connection")
                    connection.disconnect()
                    return@launch
                }
                relayConnection = connection
                connection.connect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy", e)
                updateNotification("Connection failed: ${e.message}")
            }
        }
    }

    private fun startLocalProxy() {
        // Local HTTP proxy server that forwards to relay
        localProxyServer = LocalProxyServer(
            port = 8888,
            onRequest = { method, url, headers, body ->
                relayConnection?.sendHttpRequest(method, url, headers, body)
                    ?: ProxyResponse(
                        requestId = "",
                        statusCode = 503,
                        headers = mapOf("Content-Type" to "text/plain"),
                        body = android.util.Base64.encodeToString(
                            "Relay not connected".toByteArray(),
                            android.util.Base64.NO_WRAP
                        )
                    )
            }
        )
        localProxyServer?.start()
        Log.d(TAG, "Local proxy server started on port 8888")
    }

    private suspend fun handleProxyRequest(request: ProxyRequest): ProxyResponse {
        return withContext(Dispatchers.IO) {
            try {
                // Execute HTTP request through device's network
                val connection = java.net.URL(request.url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = request.method
                connection.connectTimeout = 30000
                connection.readTimeout = 30000

                // Set headers
                request.headers.forEach { (key, value) ->
                    connection.setRequestProperty(key, value)
                }

                // Send body if present
                if (request.body != null && request.body.isNotEmpty()) {
                    connection.doOutput = true
                    connection.outputStream.use { os ->
                        os.write(android.util.Base64.decode(request.body, android.util.Base64.DEFAULT))
                    }
                }

                // Read response
                val responseCode = connection.responseCode
                val responseHeaders = mutableMapOf<String, String>()
                connection.headerFields.forEach { (key, values) ->
                    if (key != null && values.isNotEmpty()) {
                        responseHeaders[key] = values.joinToString(", ")
                    }
                }

                val responseBody = try {
                    connection.inputStream.use { it.readBytes() }
                } catch (e: Exception) {
                    connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                }

                // Track traffic (use actual byte count, not base64 length)
                val requestBodyBytes = request.body?.let {
                    try { android.util.Base64.decode(it, android.util.Base64.DEFAULT).size } catch (e: Exception) { 0 }
                } ?: 0
                totalBytesIn += responseBody.size
                totalBytesOut += requestBodyBytes

                ProxyResponse(
                    requestId = request.requestId,
                    statusCode = responseCode,
                    headers = responseHeaders,
                    body = android.util.Base64.encodeToString(responseBody, android.util.Base64.NO_WRAP)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Proxy request failed: ${e.message}")
                ProxyResponse(
                    requestId = request.requestId,
                    statusCode = 502,
                    headers = mapOf("Content-Type" to "text/plain"),
                    body = android.util.Base64.encodeToString(
                        "Proxy error: ${e.message}".toByteArray(),
                        android.util.Base64.NO_WRAP
                    )
                )
            }
        }
    }

    private fun stopProxy() {
        Log.d(TAG, "Stopping proxy service")
        // Supersede any start still in flight, so a connection being built right now is discarded
        // rather than connected after the service has been asked to stop.
        startGeneration++
        // Should already be torn down by startProxy's replace path, but disconnect() is
        // idempotent and this is the one place that must never leak a loop.
        relayConnection?.disconnect()
        relayConnection = null
        localProxyServer?.stop()
        localProxyServer = null
        isConnected = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Peer Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when proxy service is running"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        // Try to get the app's launcher activity for the notification tap action
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            null
        }

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PeerProxyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        // Use Android's built-in sync icon as fallback
        val notificationIcon = getAppIconResourceId()

        // The title is the HOST app's own label, read at runtime, never a hard-coded vendor
        // brand: the same SDK lands in every app Zealot serves (including each tenant's
        // white-label build), and a fixed "Proxies.sx Peer" title told every one of those users
        // the wrong app name. Read from PackageManager here so one build names every host
        // correctly. `check-peer-notification-title.py` gates this call staying in this method.
        val title = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(status)
            .setSmallIcon(notificationIcon)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getAppIconResourceId(): Int {
        return try {
            packageManager.getApplicationInfo(packageName, 0).icon
        } catch (e: Exception) {
            android.R.drawable.ic_dialog_info
        }
    }

    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationStats() {
        val mbIn = totalBytesIn / (1024.0 * 1024.0)
        val mbOut = totalBytesOut / (1024.0 * 1024.0)
        updateNotification("↓ %.2f MB | ↑ %.2f MB".format(mbIn, mbOut))
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ProxiesPeer::WakeLock"
        ).apply {
            setReferenceCounted(false)
            // Acquire for 24 hours max (will be released on service destroy)
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopProxy()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }
}

// Data classes for proxy communication
data class ProxyRequest(
    val requestId: String,
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?
)

data class ProxyResponse(
    val requestId: String,
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String
)
