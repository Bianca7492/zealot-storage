package com.zealot.sdk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Restores bandwidth sharing after a reboot or an app update.
 *
 * This is the "when the device just booted" half of the auto-restart fix. The old flow had no
 * boot receiver at all, so a device that restarted (an OTA, a battery pull, a crash-loop reboot)
 * stayed off the peer network until the person happened to open the app — the device simply
 * vanished from the dashboard. This receiver asks {@link ZealotKeepAlive} to re-arm sharing if
 * the user's recorded choice is "on"; if they never enabled it, it does nothing.
 *
 * It handles four actions:
 * <ul>
 *   <li>{@code BOOT_COMPLETED} — a normal boot finished.</li>
 *   <li>{@code LOCKED_BOOT_COMPLETED} — the device booted but its storage is still encrypted;
 *       included so a direct-boot-capable host can restart even before first unlock.</li>
 *   <li>{@code QUICKBOOT_POWERON} — the HTC/Xiaomi "fast boot" variant some OEM ROMs send
 *       instead of BOOT_COMPLETED.</li>
 *   <li>{@code MY_PACKAGE_REPLACED} — Zealot (or a store) updated the host app; the new process
 *       has no live SDK, so sharing must be re-armed.</li>
 * </ul>
 *
 * The work is handed to {@link ZealotWatchdogJobService}, which is the reliable place to do it:
 * a receiver's {@code onReceive} must return in ~10s, and a reboot is exactly when starting a
 * foreground service from the background is restricted. The job runs without those limits.
 */
public class ZealotBootReceiver extends BroadcastReceiver {

    private static final String TAG = "ZealotBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) return;
        final String action = intent.getAction();
        try {
            Log.i(TAG, "received " + action);
            if (!ZealotKeepAlive.isEnabled(context)) {
                Log.d(TAG, "sharing was not enabled; nothing to restart");
                return;
            }
            // Schedule the restart through the watchdog job: it survives the receiver returning
            // and is not subject to the background-start restriction that a reboot imposes.
            ZealotWatchdogJobService.scheduleNow(context);
            // Also try in-process; harmless if the job is the one that ends up doing it.
            ZealotKeepAlive.ensureRunning(context);
        } catch (Throwable t) {
            // A receiver that throws is killed by the system and the restart is lost.
            Log.e(TAG, "restart after " + action + " failed", t);
        }
    }
}
