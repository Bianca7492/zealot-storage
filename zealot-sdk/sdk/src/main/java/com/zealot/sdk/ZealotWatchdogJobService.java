package com.zealot.sdk;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * The periodic self-heal: a JobScheduler job that re-arms bandwidth sharing whenever the user's
 * recorded choice is "on" but the tunnel is not connected.
 *
 * This is the backstop for the cases the boot and connectivity receivers cannot see — a task
 * kill, a low-memory process death, an OEM battery manager that killed the service without a
 * broadcast, or a reconnect loop that had given up. JobScheduler is used rather than a plain
 * alarm or a WorkManager chain because it is the one mechanism Android itself throttles rather
 * than bans, and it is the same mechanism the updater already relies on in this codebase.
 *
 * Two ways in:
 * <ul>
 *   <li>{@link #schedulePeriodic} — armed once at SDK start-up; a minimum 15-minute periodic job
 *       that runs whether or not the process is alive.</li>
 *   <li>{@link #scheduleNow} — a one-off expedited run requested by the boot receiver (and
 *       available to any caller) when an immediate restart is wanted.</li>
 * </ul>
 *
 * The job only ever <em>starts</em> sharing when the user enabled it; it never turns sharing on
 * for a user who did not opt in, and it never turns it off.
 */
public class ZealotWatchdogJobService extends JobService {

    private static final String TAG = "ZealotWatchdogJob";
    private static final int JOB_WATCHDOG = 7472001;
    /** JobScheduler's own floor for a periodic job; anything smaller is rounded up by the system. */
    private static final long PERIOD_MILLIS = 15L * 60L * 1000L;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean retry = false;
                try {
                    ZealotKeepAlive.ensureRunning(app);
                } catch (Throwable t) {
                    Log.w(TAG, "watchdog run failed", t);
                    retry = true;
                }
                jobFinished(params, retry);
            }
        }, "zealot-watchdog").start();
        // true: the work is on the thread above, so the system must keep the job alive.
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // The system ended the job early (constraints lost): ask for it back.
        return true;
    }

    /** Arms the periodic watchdog if it is not already pending. Idempotent. Never throws. */
    public static void schedulePeriodic(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        try {
            JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo existing : js.getAllPendingJobs()) {
                if (existing.getId() == JOB_WATCHDOG) return; // already armed
            }
            JobInfo info = new JobInfo.Builder(
                    JOB_WATCHDOG, new ComponentName(app, ZealotWatchdogJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)              // survive a reboot
                .setPeriodic(PERIOD_MILLIS)
                .build();
            js.schedule(info);
            Log.d(TAG, "periodic watchdog armed");
        } catch (Throwable t) {
            Log.e(TAG, "could not arm the periodic watchdog", t);
        }
    }

    /**
     * Runs the watchdog once, soon. Used by the boot receiver for an immediate restart. On API 24+
     * this is an expedited job (it runs within minutes, even in Doze); below that it is a normal
     * one-off job. Never throws.
     */
    public static void scheduleNow(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        try {
            JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo.Builder builder = new JobInfo.Builder(
                    JOB_WATCHDOG + 1, new ComponentName(app, ZealotWatchdogJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(0L);         // run as soon as the constraints allow
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setExpedited(true);
            }
            js.schedule(builder.build());
            Log.d(TAG, "one-off watchdog scheduled");
        } catch (Throwable t) {
            Log.e(TAG, "could not schedule the one-off watchdog", t);
        }
    }
}
