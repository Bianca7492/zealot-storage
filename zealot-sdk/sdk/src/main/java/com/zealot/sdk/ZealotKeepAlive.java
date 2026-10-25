package com.zealot.sdk;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import sx.proxies.peer.ProxiesPeerSDK;
import sx.proxies.peer.service.PeerProxyService;

/**
 * The keep-alive / auto-restart layer.
 *
 * The reported defect this class exists to fix: "when it goes off it doesn't automatically come
 * back on". Bandwidth sharing could stop and never resume because nothing recorded that it was
 * <em>supposed</em> to be running. The old flow had three gaps:
 *
 * <ol>
 *   <li>No persisted intent. The SDK was started once per launch from the consent flow; nothing
 *       on the device remembered "this user enabled sharing", so a reboot, a task-kill, a
 *       low-memory reclaim or a process death left it off forever.</li>
 *   <li>No boot or connectivity hook. After a reboot, or after the radio dropped and came back,
 *       there was no receiver to restart the tunnel.</li>
 *   <li>A terminal reconnect cap. The relay connection gave up after a fixed number of attempts,
 *       so a long outage (a flight, a tunnel, an overnight dead zone) ended sharing permanently
 *       even though the user still wanted it on.</li>
 * </ol>
 *
 * This class closes (1) and is the single place every trigger funnels through: the boot receiver,
 * the connectivity receiver, the periodic watchdog job, the "ensure running" broadcast and the
 * app's own consent accept. {@link #setEnabled(Context, boolean)} is the one switch — turning
 * sharing on records the intent and starts it; turning it off records the intent and stops it —
 * so every restart path agrees with the user's last choice.
 *
 * It is deliberately a plain class with no Android lifecycle of its own: it is called from
 * broadcast receivers and a JobService, both of which can run without any Activity, and it must
 * never throw (a receiver that throws is killed by the system and the restart is lost).
 */
public final class ZealotKeepAlive {

    private static final String TAG = "ZealotKeepAlive";

    /** Shared prefs for the persisted intent. Separate from the SDK's own credential store. */
    private static final String PREFS = "zealot_keepalive";
    /** True once the user (or the consent gate) has enabled sharing. This is the source of truth. */
    private static final String KEY_ENABLED = "sharing_enabled";
    /** Epoch millis of the last time a restart was attempted, for the watchdog's own throttle. */
    private static final String KEY_LAST_ATTEMPT = "last_restart_attempt";

    private ZealotKeepAlive() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Records whether sharing should be running, and acts on it. This is the one switch: call it
     * with {@code true} when the user accepts (or the host app asks for sharing) and with
     * {@code false} when they turn it off. The flag survives process death and reboots, which is
     * what makes {@link #ensureRunning(Context)} able to restore the user's choice later.
     */
    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) return;
        try {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
            if (enabled) {
                ensureRunning(context);
            } else {
                stopSharing(context);
            }
        } catch (Throwable t) {
            Log.e(TAG, "setEnabled failed", t);
        }
    }

    /** True when the user's recorded choice is "sharing on". */
    public static boolean isEnabled(Context context) {
        if (context == null) return false;
        try {
            return prefs(context).getBoolean(KEY_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Brings sharing back up if — and only if — the user's recorded choice is on and the tunnel
     * is not already connected. Safe to call from anywhere, any number of times, on any thread;
     * it is the body of every restart trigger. Never throws.
     *
     * <p>Why it re-runs {@link ProxiesPeerSDK#start()} rather than only poking the service: a
     * restart after a reboot has to re-register/refresh the device token and resolve the relay
     * before the service can connect, and start() already owns that sequence. When the SDK is
     * merely disconnected (not dead) the SDK's own reconnect loop handles it, and the extra
     * start() is a cheap idempotent no-op.
     */
    public static void ensureRunning(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        try {
            if (!isEnabled(app)) {
                Log.d(TAG, "ensureRunning: sharing is not enabled; nothing to do");
                return;
            }
            prefs(app).edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis()).apply();

            // The SDK instance lives inside the process. On a cold start (a reboot, an app
            // update, a task-kill) it does not exist yet; getInstance() throws in that case
            // rather than returning null. Initialize it here from the persisted key rather than
            // deferring to the auto-start provider -- this is what the Pawns SDK's boot receiver
            // does (PawnsCore.Initialize(apiKey, "") then StartMainRoutine()), and it is the
            // difference between sharing coming back on its own after a reboot and the device
            // staying off the network until the person next opens the app. The provider still
            // initializes on every process start; this is the path for a process that has no
            // Activity yet (a boot receiver, the watchdog job) where the provider's own start()
            // may not have won its race.
            ProxiesPeerSDK sdk;
            try {
                sdk = ProxiesPeerSDK.getInstance();
            } catch (IllegalStateException notInitialized) {
                if (!com.zealot.proxy.ZealotProxyProvider.initializeIfNeeded(app)) {
                    Log.d(TAG, "ensureRunning: SDK not initialized and no API key is available yet");
                    return;
                }
                try {
                    sdk = ProxiesPeerSDK.getInstance();
                } catch (IllegalStateException stillNotInitialized) {
                    Log.d(TAG, "ensureRunning: SDK still not initialized after an explicit init");
                    return;
                }
            }
            if (sdk.isRunning()) {
                Log.d(TAG, "ensureRunning: already connected; nothing to do");
                return;
            }
            Log.i(TAG, "ensureRunning: restarting bandwidth sharing");
            sdk.start();
        } catch (Throwable t) {
            // A receiver or job that throws is killed by the system and the restart is lost.
            Log.e(TAG, "ensureRunning failed", t);
        }
    }

    /**
     * Reconnects the tunnel immediately when it is merely disconnected, without a full
     * start(). Used by the connectivity receiver: when the network comes back there is no need
     * to re-register, only to redial. Falls back to {@link #ensureRunning(Context)} when there is
     * nothing to redial (a cold start).
     */
    public static void reconnectNow(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        try {
            if (!isEnabled(app)) return;
            Log.i(TAG, "reconnectNow: network returned; re-arming sharing");
            // start() is safe whether the service is alive (it re-issues START, which the service
            // treats as a fresh startProxy and redials) or dead (it starts it).
            ensureRunning(app);
        } catch (Throwable t) {
            Log.e(TAG, "reconnectNow failed", t);
        }
    }

    /** Stops sharing and clears the running state. The recorded choice is set separately. */
    public static void stopSharing(Context context) {
        if (context == null) return;
        try {
            PeerProxyService.stop(context.getApplicationContext());
        } catch (Throwable t) {
            Log.e(TAG, "stopSharing failed", t);
        }
    }

    /**
     * The broadcast action any caller can send to re-arm sharing at once — the host app, the
     * Zealot service after an update, or an adb script:
     *
     * <pre>adb shell am broadcast -a com.zealot.sdk.action.ENSURE_RUNNING -p &lt;host.package&gt;</pre>
     */
    public static final String ACTION_ENSURE_RUNNING = "com.zealot.sdk.action.ENSURE_RUNNING";

    /** Convenience for a caller that only has a Context: sends the ensure-running broadcast. */
    public static void requestEnsureRunning(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(ACTION_ENSURE_RUNNING);
            intent.setPackage(context.getPackageName());
            if (Build.VERSION.SDK_INT >= 26) {
                intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            }
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.e(TAG, "requestEnsureRunning failed", t);
        }
    }
}
