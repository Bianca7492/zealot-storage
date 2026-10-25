package com.zealot.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Task 47a: two inputs, both from this app's own PendingIntents (declared with android:exported="false"):
 * the tap on the "update available" notification (start the download job) and PackageInstaller's status report
 * for the session this library committed.
 */
public class ZealotUpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        try {
            if (ZealotUpdater.ACTION_DOWNLOAD.equals(intent.getAction())) {
                ZealotUpdater.scheduleDownload(context);
            } else if (ZealotUpdater.ACTION_INSTALL_STATUS.equals(intent.getAction())) {
                ZealotUpdater.handleInstallStatus(context, intent);
            }
        } catch (Throwable t) {
            Log.w("ZealotUpdater", "receiver failed", t);
        }
    }
}
