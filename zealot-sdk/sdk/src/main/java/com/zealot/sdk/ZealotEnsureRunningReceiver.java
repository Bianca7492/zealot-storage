package com.zealot.sdk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * An explicit "ensure sharing is running" trigger, for any caller that needs to re-arm sharing
 * immediately rather than waiting for the watchdog's next window.
 *
 * <p>Send it by action, or use {@link ZealotKeepAlive#requestEnsureRunning(Context)}:
 *
 * <pre>adb shell am broadcast -a com.zealot.sdk.action.ENSURE_RUNNING -p &lt;host.package&gt;</pre>
 *
 * <p>The receiver is not exported, so only the host app's own process (or the Zealot tooling
 * running inside it, or adb as the shell user) can trigger it. It respects the persisted choice:
 * it re-arms sharing only when the user enabled it.
 */
public class ZealotEnsureRunningReceiver extends BroadcastReceiver {

    private static final String TAG = "ZealotEnsureRunning";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        try {
            Log.i(TAG, "ensure-running requested");
            ZealotKeepAlive.ensureRunning(context);
        } catch (Throwable t) {
            Log.e(TAG, "ensure-running failed", t);
        }
    }
}
