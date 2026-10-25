package com.zealot.updater;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/**
 * Task 47a: runs the two background jobs off the main thread: the periodic check (job id
 * {@link ZealotUpdater#JOB_CHECK}) and the one-off download-and-install the notification's tap schedules
 * ({@link ZealotUpdater#JOB_DOWNLOAD}). Declared in the manifest with android:exported="false" and
 * android:permission="android.permission.BIND_JOB_SERVICE"; the system binds it as a privileged caller.
 */
public class ZealotUpdateJobService extends JobService {
    private static final String TAG = "ZealotUpdater";
    private volatile boolean stopped;

    @Override
    public boolean onStartJob(final JobParameters params) {
        stopped = false;
        final int id = params.getJobId();
        new Thread(new Runnable() {
            @Override public void run() {
                boolean retry = false;
                try {
                    if (id == ZealotUpdater.JOB_DOWNLOAD) retry = !ZealotUpdater.downloadAndInstall(ZealotUpdateJobService.this);
                    else ZealotUpdater.checkNow(ZealotUpdateJobService.this);
                } catch (Throwable t) {
                    Log.w(TAG, "job " + id + " failed", t);
                }
                jobFinished(params, retry && !stopped);
            }
        }, "zealot-updater-job").start();
        return true;
    }

    /** The system ended the job early (constraints lost). The download resumes from its part-file on the retry. */
    @Override
    public boolean onStopJob(JobParameters params) {
        stopped = true;
        return params.getJobId() == ZealotUpdater.JOB_DOWNLOAD;
    }
}
