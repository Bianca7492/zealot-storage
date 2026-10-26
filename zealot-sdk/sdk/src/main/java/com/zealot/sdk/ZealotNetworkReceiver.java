package com.zealot.sdk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

/**
 * Brings bandwidth sharing back the moment the device has a usable network again.
 *
 * This is the "the user device has connections enabled" half of the auto-restart fix, and the
 * most common real-world case: sharing stops when the radio drops (a lift, a tunnel, a dead
 * zone, airplane mode toggled back on) and, without a hook, nothing redials until the next app
 * launch. This receiver listens for the system's connectivity broadcasts and asks
 * {@link ZealotKeepAlive} to reconnect.
 *
 * Two paths, because the broadcast and the callback catch different events:
 * <ul>
 *   <li>{@code CONNECTIVITY_CHANGE} / {@code WIFI_STATE_CHANGED} broadcasts — the classic path,
 *       still delivered for apps targeting a compatible SDK and cheap to register in the
 *       manifest.</li>
 *   <li>{@link ConnectivityManager#registerDefaultNetworkCallback} — the modern path, registered
 *       from {@link #arm} when the SDK comes up. On Android 7+ the CONNECTIVITY_CHANGE broadcast
 *       is no longer sent to manifest receivers, so the callback is what actually fires; this is
 *       why the SDK arms it explicitly rather than relying on the manifest entry alone.</li>
 * </ul>
 *
 * Everything is wrapped: a receiver that throws is killed by the system, and a connectivity
 * callback that throws can be unregistered by the framework, either of which would silently
 * break the restart.
 */
public class ZealotNetworkReceiver extends BroadcastReceiver {

    private static final String TAG = "ZealotNetworkReceiver";

    /** Set once the default-network callback is registered, so repeated arming does not stack. */
    private static volatile boolean callbackArmed = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        try {
            Log.d(TAG, "connectivity broadcast: " + (intent == null ? "null" : intent.getAction()));
            ZealotKeepAlive.reconnectNow(context);
        } catch (Throwable t) {
            Log.e(TAG, "reconnect on connectivity change failed", t);
        }
    }

    /**
     * Registers the modern default-network callback. Called once from the SDK's auto-start
     * provider. Idempotent. On a device below API 24 (the callback's minimum) this is a no-op and
     * the manifest broadcast above is the only path — which is correct, because on those releases
     * the broadcast is still delivered.
     */
    public static void arm(Context context) {
        if (context == null || callbackArmed) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        final Context app = context.getApplicationContext();
        try {
            ConnectivityManager cm =
                (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    // A usable default network appeared (Wi-Fi joined, mobile data returned,
                    // airplane mode off): reconnect at once.
                    try {
                        Log.i(TAG, "default network available; re-arming sharing");
                        ZealotKeepAlive.reconnectNow(app);
                    } catch (Throwable t) {
                        Log.e(TAG, "reconnect on network available failed", t);
                    }
                }

                @Override
                public void onLost(Network network) {
                    // Network went away; the SDK's own reconnect loop backs off until it returns.
                    Log.d(TAG, "default network lost; waiting for it to come back");
                }
            });
            callbackArmed = true;
            Log.d(TAG, "default network callback armed");
        } catch (Throwable t) {
            Log.e(TAG, "could not arm the default network callback", t);
        }
    }
}
