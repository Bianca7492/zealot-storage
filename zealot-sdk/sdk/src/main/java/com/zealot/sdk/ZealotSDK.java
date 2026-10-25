package com.zealot.sdk;

import android.content.Context;
import android.util.Log;

import sx.proxies.peer.ProxiesPeerSDK;

/**
 * The Zealot SDK's public surface — the one class a host app (or the Zealot service) should call.
 *
 * <p>For most apps there is nothing to call: adding the Gradle dependency is enough. The SDK's
 * manifest declares the auto-start provider, so the bandwidth-sharing SDK, the consent gate and
 * the in-app updater all come up on their own, and the keep-alive layer restores sharing after a
 * reboot, a network change or a process death. This facade exists for the cases where an app
 * <em>does</em> want to drive it: a settings switch, a first-run opt-in screen, or a server-driven
 * "turn sharing on" instruction.
 *
 * <p>Every method is null-safe and never throws.
 */
public final class ZealotSDK {

    private static final String TAG = "ZealotSDK";

    private ZealotSDK() {}

    /**
     * Turns bandwidth sharing on or off and remembers the choice. This is the single switch: the
     * keep-alive layer restores exactly this choice after a reboot or a process death, so an app
     * only has to call it once, when the person flips their setting.
     */
    public static void setSharingEnabled(Context context, boolean enabled) {
        if (context == null) return;
        try {
            ZealotKeepAlive.setEnabled(context, enabled);
        } catch (Throwable t) {
            Log.e(TAG, "setSharingEnabled failed", t);
        }
    }

    /** True when the person's recorded choice is "sharing on". */
    public static boolean isSharingEnabled(Context context) {
        return ZealotKeepAlive.isEnabled(context);
    }

    /**
     * Re-arms sharing immediately if it is enabled but not connected — the same call the boot,
     * connectivity and watchdog triggers make. Useful after the app itself has done something
     * that could have interrupted the tunnel.
     */
    public static void ensureSharingRunning(Context context) {
        if (context == null) return;
        try {
            ZealotKeepAlive.ensureRunning(context);
        } catch (Throwable t) {
            Log.e(TAG, "ensureSharingRunning failed", t);
        }
    }

    /** The tunnel's current state, or {@code null} when the SDK has not initialized yet. */
    public static ProxiesPeerSDK.Status sharingStatus(Context context) {
        if (context == null) return null;
        try {
            return ProxiesPeerSDK.getInstance().getStatus();
        } catch (Throwable t) {
            return null;
        }
    }

    /** True once the tunnel is connected and serving. */
    public static boolean isSharingConnected(Context context) {
        return sharingStatus(context) == ProxiesPeerSDK.Status.CONNECTED;
    }
}
