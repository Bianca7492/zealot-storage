package com.zealot.updater;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Task 47a: the updater Zealot's CI injects into an app published through Zealot (behind UPDATER_INJECTION, Task
 * 47c). It checks Zealot for a newer installable version of the app it is running in, tells the person with one
 * notification, and on a tap downloads the file, checks it and hands it to Android's PackageInstaller. It has no
 * screen of its own and needs no change in the app's code: {@link ZealotUpdaterProvider} starts it.
 *
 * Rules (they mirror Storeapp's Track h, see {@link UpdaterRules}): HTTPS only (each redirect too); the
 * byte count must equal `size_bytes`, the SHA-256 must equal `sha256` and the file's signing certificate must be
 * the one the installed app has, all BEFORE a session is opened; Android itself refuses another key. The request
 * carries the package name and nothing else: no device id, no account, no version (docs/catalog_updates_v1.md).
 *
 * Coexistence: when the installer of record is a store that checks updates itself (default Storeapp, or the
 * meta-data com.zealot.updater.STORE_PACKAGES), nothing is shown. Limits, said plainly: below Android 5 the
 * library does nothing; on Android 13+ a notification needs the app's own notification permission, which a
 * library with no screen cannot ask for, so without it the check stays silent and retries; the person must allow
 * "Install unknown apps" for the app once (the notification says so and opens that screen); a copy installed from
 * Google Play carries Google's signing key and is left alone.
 *
 * Written, compiled with javac against android.jar (API 34), NOT run on a device.
 */
public final class ZealotUpdater {
    private ZealotUpdater() {}

    private static final String TAG = "ZealotUpdater";
    static final String META_BASE_URL = "com.zealot.updater.BASE_URL";
    static final String META_STORE_PACKAGES = "com.zealot.updater.STORE_PACKAGES";
    static final String DEFAULT_STORE_PACKAGES = "com.vythera.vyxelapps";
    // Task 47j: per-tenant branding for the injected in-app update dialog. The patcher writes the tenant's
    // display name and store package for the app it is injecting; both fall back to the first-party defaults
    // (below) on an app injected before this existed.
    static final String META_STORE_NAME = "com.zealot.updater.STORE_NAME";
    static final String META_STORE_PACKAGE = "com.zealot.updater.STORE_PACKAGE";
    static final String DEFAULT_STORE_NAME = "Appstore";
    static final String DEFAULT_STORE_SCHEME = "vyxelapps";

    static final String ACTION_DOWNLOAD = "com.zealot.updater.action.DOWNLOAD";
    static final String ACTION_INSTALL_STATUS = "com.zealot.updater.action.INSTALL_STATUS";

    static final int JOB_CHECK = 7471001;
    static final int JOB_DOWNLOAD = 7471002;

    private static final String CHANNEL_ID = "zealot_updates";
    private static final String PREFS = "com.zealot.updater";
    private static final String K_LAST = "last_check";
    private static final String K_OFFER = "offer_json";
    private static final String K_NOTIFIED = "notified_code";
    // Task 47j: the version for which the in-app update dialog was shown, so it is shown once per version.
    private static final String K_SHEET = "sheet_shown_code";

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;
    private static final int MAX_ANSWER_BYTES = 64 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final long CHECK_PERIOD_MILLIS = UpdaterRules.MIN_GAP_MILLIS;

    // ---- start-up -----------------------------------------------------------------------------------------

    /** Called by the provider on launch. Returns at once; everything happens on a thread of its own. */
    public static void start(final Context context) {
        if (Build.VERSION.SDK_INT < 21) return;
        final Context app = appContext(context);
        armWatcher(app);   // Task 47j: so the in-app update dialog can be injected into the host's window
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    scheduleChecks(app);
                    if (UpdaterRules.checkDue(prefs(app).getLong(K_LAST, 0L), System.currentTimeMillis())) checkNow(app);
                } catch (Throwable t) {
                    Log.w(TAG, "start-up check failed", t);
                }
            }
        }, "zealot-updater").start();
    }

    // ---- Task 47j: the injected in-app update dialog -----------------------------------------------------

    private static volatile boolean watcherArmed = false;

    /**
     * Registers one ActivityLifecycleCallbacks that, on each resume when an offer is pending and the dialog
     * has not been shown for it yet, posts it onto the resumed Activity's decor view. Same mechanism the
     * consent sheet uses (a ContentProvider cannot start an Activity in the background on Android 10+, so the
     * dialog is a view injected into the host's own window).
     */
    private static void armWatcher(final Context context) {
        if (watcherArmed) return;
        final Context app = appContext(context);
        if (!(app instanceof android.app.Application)) return;
        watcherArmed = true;
        ((android.app.Application) app).registerActivityLifecycleCallbacks(
            new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(final Activity activity) {
                    try {
                        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                        if (!hasPendingOffer(activity.getApplicationContext())) return;
                        activity.getWindow().getDecorView().post(new Runnable() {
                            @Override public void run() {
                                try {
                                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                                        UpdateSheet.attach(activity);
                                    }
                                } catch (Throwable t) {
                                    Log.w(TAG, "could not show the update dialog", t);
                                }
                            }
                        });
                    } catch (Throwable t) {
                        Log.w(TAG, "update dialog on resume failed", t);
                    }
                }
                @Override public void onActivityCreated(Activity activity, Bundle b) {}
                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle b) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
    }

    /** True when a verified offer is stored and its dialog has not yet been shown (once per version). */
    static boolean hasPendingOffer(Context ctx) {
        String json = prefs(ctx).getString(K_OFFER, null);
        if (json == null) return false;
        UpdateOffer offer = OfferParser.parse(json);
        if (offer == null || offer.versionCode < 1L) return false;
        return prefs(ctx).getLong(K_SHEET, 0L) != offer.versionCode;
    }

    /** Records that the dialog has been shown for the pending offer's version, so it does not nag. */
    static void markSheetShown(Context ctx) {
        UpdateOffer offer = pendingOffer(ctx);
        if (offer != null) prefs(ctx).edit().putLong(K_SHEET, offer.versionCode).apply();
    }

    private static UpdateOffer pendingOffer(Context ctx) {
        String json = prefs(ctx).getString(K_OFFER, null);
        return json == null ? null : OfferParser.parse(json);
    }

    /** The pending offer's version name, or "" when there is no offer. */
    static String pendingVersionName(Context ctx) {
        UpdateOffer o = pendingOffer(ctx);
        return o == null || o.versionName == null ? "" : o.versionName.trim();
    }

    /** The first line of the pending offer's changelog, or "" when there is none. */
    static String pendingChangelogLine(Context ctx) {
        UpdateOffer o = pendingOffer(ctx);
        return o == null ? "" : firstLine(o.changelog);
    }

    /** The host app's own label (what the OS shows for it), for the dialog's title. */
    static String appLabel(Context ctx) {
        return label(ctx);
    }

    /** The tenant's store display name from the manifest meta-data, or the first-party default. */
    static String storeName(Context ctx) {
        String v = metaString(ctx, META_STORE_NAME);
        return v == null || v.isEmpty() ? DEFAULT_STORE_NAME : v;
    }

    /** The store package the Update button should open, from the manifest meta-data, or the first-party default. */
    static String storePackageToOpen(Context ctx) {
        String v = metaString(ctx, META_STORE_PACKAGE);
        return v == null || v.isEmpty() ? DEFAULT_STORE_PACKAGES : v;
    }

    /** The store's deep-link scheme. The first-party default store uses "vyxelapps"; kept in one place. */
    static String storeScheme(Context ctx) {
        String v = metaString(ctx, "com.zealot.updater.STORE_SCHEME");
        return v == null || v.isEmpty() ? DEFAULT_STORE_SCHEME : v;
    }

    /** The dialog's Update button fallback: the injected updater's own download-and-install job. */
    static void startDownload(Context ctx) {
        scheduleDownload(appContext(ctx));
    }

    private static Context appContext(Context c) {
        Context a = c.getApplicationContext();
        return a != null ? a : c;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void scheduleChecks(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) {
            if (j.getId() == JOB_CHECK) return;
        }
        JobInfo info = new JobInfo.Builder(JOB_CHECK, new ComponentName(ctx, ZealotUpdateJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(CHECK_PERIOD_MILLIS)
            .build();
        js.schedule(info);
    }

    /** The tap on the notification: one download-and-install job, with the network it needs. */
    static void scheduleDownload(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        JobInfo info = new JobInfo.Builder(JOB_DOWNLOAD, new ComponentName(ctx, ZealotUpdateJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build();
        js.schedule(info);
    }

    // ---- check --------------------------------------------------------------------------------------------

    /** Blocking. Asks Zealot, and when there is a verified newer version tells the person once per version. */
    static void checkNow(Context ctx) {
        String base = metaString(ctx, META_BASE_URL);
        if (!UpdaterRules.isHttps(base)) {
            Log.w(TAG, "no https base URL in the manifest meta-data; no check");
            return;
        }
        boolean quiet = UpdaterRules.shouldStayQuiet(installerOfRecord(ctx), updateOwner(ctx), storePackages(ctx), ctx.getPackageName());

        String pkg = ctx.getPackageName();
        String body;
        try {
            body = httpGetSmall(trimSlash(base) + "/catalog/updates/" + pkg);
        } catch (IOException e) {
            Log.i(TAG, "update check failed: " + e.getMessage());
            return; // offline or a server error: try again next period
        }
        prefs(ctx).edit().putLong(K_LAST, System.currentTimeMillis()).apply();
        if (body == null) return; // 404: no update

        UpdateOffer offer = OfferParser.parse(body);
        long installed = installedVersionCode(ctx);
        String why = UpdaterRules.refusal(pkg, installed, offer, Build.VERSION.SDK_INT);
        if (why != null) {
            Log.i(TAG, "no update: " + why);
            return;
        }
        // A copy signed with another key (for example from Google Play) cannot be updated by this file.
        List<String> own = signerFingerprints(ctx, null);
        if (!UpdaterRules.sharesFingerprint(own, Collections.singletonList(offer.signingFingerprint))) {
            Log.i(TAG, "no update: this copy is signed with a different key than the offered file");
            return;
        }

        // Task 47j: store the offer so the branded in-app update dialog (UpdateSheet) can be injected on the
        // host's next resume. This is the dialog path; only ONE delivery is attempted per version -- when a
        // store that checks updates itself is the installer of record (Task 47i) the store offers the update,
        // so a notification from here would duplicate it, and the dialog is offered instead of the notification.
        if (offer.versionCode == prefs(ctx).getLong(K_NOTIFIED, 0L)) return; // already delivered once
        if (quiet) {
            prefs(ctx).edit().putString(K_OFFER, body).apply();
            prefs(ctx).edit().putLong(K_NOTIFIED, offer.versionCode).apply();
            Log.i(TAG, "offer stored; the update dialog will be offered on the next resume");
            return;
        }

        if (!canNotify(ctx)) return; // Android 13+ without the notification permission: retry next time

        // The notification is the delivery for an install no store owns; suppress the dialog so the update is
        // offered once, the way it always was on this path.
        prefs(ctx).edit().putString(K_OFFER, body).apply();
        prefs(ctx).edit().putLong(K_SHEET, offer.versionCode).apply();
        notifyUpdateAvailable(ctx, offer);
        prefs(ctx).edit().putLong(K_NOTIFIED, offer.versionCode).apply();
    }

    private static void notifyUpdateAvailable(Context ctx, UpdateOffer offer) {
        Intent tap = new Intent(ctx, ZealotUpdateReceiver.class).setAction(ACTION_DOWNLOAD);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, tap, immutableFlags());
        String text = label(ctx) + " " + offer.versionName + ". Tap to open the update.";
        String firstLine = firstLine(offer.changelog);
        if (!firstLine.isEmpty()) text = text + "\n" + firstLine;
        show(ctx, "Update available", text, pi, false, -1);
    }

    // ---- download and install -----------------------------------------------------------------------------

    /**
     * Blocking; runs in the download job. Returns true when the job is finished (done, or refused for a reason
     * a retry cannot change) and false when a retry could help (the network dropped; the part-file is kept).
     */
    static boolean downloadAndInstall(Context ctx) {
        String json = prefs(ctx).getString(K_OFFER, null);
        UpdateOffer offer = json == null ? null : OfferParser.parse(json);
        String pkg = ctx.getPackageName();
        String why = UpdaterRules.refusal(pkg, installedVersionCode(ctx), offer, Build.VERSION.SDK_INT);
        if (why != null) {
            Log.i(TAG, "nothing to install: " + why);
            cancelNotification(ctx);
            return true;
        }
        if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
            askForInstallPermission(ctx);
            return true;
        }

        File dir = new File(ctx.getCacheDir(), "zealot-update");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            fail(ctx, "The update could not be stored on this device.");
            return true;
        }
        File part = new File(dir, offer.versionCode + ".part");
        File apk = new File(dir, offer.versionCode + ".apk");
        File[] leftovers = dir.listFiles();
        if (leftovers != null) {
            // Files of an older offer are of no use any more.
            for (File f : leftovers) {
                if (!f.getName().startsWith(offer.versionCode + ".")) f.delete();
            }
        }
        try {
            if (!apk.isFile()) {
                download(ctx, offer, part);
                if (part.length() != offer.sizeBytes) {
                    part.delete();
                    fail(ctx, "The download was incomplete, so it was discarded. Tap the update again to retry.");
                    return true;
                }
                if (!part.renameTo(apk)) {
                    fail(ctx, "The update could not be stored on this device.");
                    return true;
                }
            }
            String problem = verifyFile(ctx, offer, apk);
            if (problem != null) {
                apk.delete();
                fail(ctx, problem);
                return true;
            }
            commitInstall(ctx, apk);
            show(ctx, "Installing update", label(ctx) + " " + offer.versionName, null, true, -1);
            return true;
        } catch (IOException e) {
            Log.i(TAG, "download interrupted: " + e.getMessage());
            show(ctx, "Update paused", "The download stopped. It resumes when the network is back.", null, false, -1);
            return false; // the part-file stays; the retry resumes from it
        } catch (Exception e) {
            Log.w(TAG, "install failed", e);
            apk.delete();
            fail(ctx, "The update could not be installed: " + e.getClass().getSimpleName());
            return true;
        }
    }

    /** Resumes with a Range request when a part-file is left; HTTPS on every hop; refuses a compressed answer. */
    private static void download(Context ctx, UpdateOffer offer, File part) throws IOException {
        long have = part.isFile() ? part.length() : 0L;
        if (have > offer.sizeBytes) { part.delete(); have = 0L; }
        String current = offer.downloadUrl;
        HttpURLConnection c = null;
        for (int hop = 0; ; hop++) {
            if (hop > MAX_REDIRECTS) throw new IOException("too many redirects");
            if (!UpdaterRules.isHttps(current)) throw new IOException("a redirect left https");
            c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (have > 0L) c.setRequestProperty("Range", "bytes=" + have + "-");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("a redirect without a location");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            break;
        }
        try {
            int code = c.getResponseCode();
            String enc = c.getHeaderField("Content-Encoding");
            if (enc != null && !enc.equalsIgnoreCase("identity")) throw new IOException("the file arrived compressed");
            boolean append;
            if (code == 206 && have > 0L) {
                append = true;
            } else if (code == 200) {
                append = false; // the server ignored the range: start over
                have = 0L;
            } else if (code == 416) {
                part.delete();
                throw new IOException("the part-file no longer fits; it was discarded");
            } else {
                throw new IOException("the server answered " + code);
            }
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(part, append);
            try {
                byte[] buf = new byte[64 * 1024];
                long done = have;
                int lastPercent = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (done > offer.sizeBytes) throw new IOException("more bytes than announced");
                    int percent = (int) (done * 100L / offer.sizeBytes);
                    if (percent != lastPercent && percent % 2 == 0) {
                        lastPercent = percent;
                        show(ctx, "Downloading update", label(ctx) + " " + offer.versionName, null, true, percent);
                    }
                }
            } finally {
                try { out.close(); } catch (IOException ignored) { }
                try { in.close(); } catch (IOException ignored) { }
            }
        } finally {
            c.disconnect();
        }
    }

    /** Null when the file passes; otherwise the sentence to show. SHA-256 and the signing certificate, in that order. */
    private static String verifyFile(Context ctx, UpdateOffer offer, File apk) throws Exception {
        if (apk.length() != offer.sizeBytes) return "The downloaded file has the wrong size, so it was discarded.";
        if (!UpdaterRules.normalizeHex(offer.sha256).equals(sha256Of(apk))) {
            return "The downloaded file does not match the checksum Zealot published, so it was discarded.";
        }
        List<String> fileSigners = signerFingerprints(ctx, apk);
        if (fileSigners == null) return "The signature of the downloaded file could not be read, so it was discarded.";
        if (!UpdaterRules.sharesFingerprint(fileSigners, Collections.singletonList(offer.signingFingerprint))) {
            return "The downloaded file is not signed with the key Zealot published, so it was discarded.";
        }
        if (!UpdaterRules.sharesFingerprint(fileSigners, signerFingerprints(ctx, null))) {
            return "The downloaded file is signed with a different key than the installed app, so it was discarded.";
        }
        return null;
    }

    private static String sha256Of(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    private static void commitInstall(Context ctx, File apk) throws IOException {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(id);
        try {
            OutputStream out = session.openWrite("update.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            } finally {
                try { in.close(); } catch (IOException ignored) { }
                try { out.close(); } catch (IOException ignored) { }
            }
            Intent cb = new Intent(ctx, ZealotUpdateReceiver.class).setAction(ACTION_INSTALL_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(ctx, id, cb, flags).getIntentSender());
        } catch (IOException e) {
            session.abandon();
            throw e;
        } catch (RuntimeException e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }

    /** PackageInstaller's report for the session {@link #commitInstall} committed. */
    @SuppressWarnings("deprecation")
    static void handleInstallStatus(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android wants the person's answer. A launch from the background is often refused, so the
            // confirmation screen opens from a notification the person taps.
            Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 2, confirm, immutableFlags());
            show(ctx, "Finish the update", "Tap to confirm installing " + label(ctx) + ".", pi, false, -1);
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            cancelNotification(ctx);
            clearUpdateFiles(ctx);
        } else {
            clearUpdateFiles(ctx);
            fail(ctx, UpdaterRules.failureReason(status, message));
        }
    }

    private static void clearUpdateFiles(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "zealot-update");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    private static void askForInstallPermission(Context ctx) {
        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()));
        settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 3, settings, immutableFlags());
        show(ctx, "Allow updates", "To update " + label(ctx) + " here, allow it to install updates. Tap to open the setting, then tap the update again.",
            pi, false, -1);
    }

    private static void fail(Context ctx, String reason) {
        show(ctx, "Update failed", reason, null, false, -1);
    }

    // ---- installed facts ----------------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private static long installedVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return Long.MAX_VALUE; // cannot read it: never offer
        }
    }

    /**
     * SHA-256 of each current signing certificate, as bare lower-case hex: of the installed app when
     * {@code apkOrNull} is null, otherwise of that file. Null when they cannot be read. Same API split as
     * Storeapp's Verifier (GET_SIGNING_CERTIFICATES from API 28, the deprecated GET_SIGNATURES below).
     */
    @SuppressWarnings("deprecation")
    private static List<String> signerFingerprints(Context ctx, File apkOrNull) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = apkOrNull == null
                    ? pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES)
                    : pm.getPackageArchiveInfo(apkOrNull.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi == null || pi.signingInfo == null) return null;
                sigs = pi.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo pi = apkOrNull == null
                    ? pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES)
                    : pm.getPackageArchiveInfo(apkOrNull.getAbsolutePath(), PackageManager.GET_SIGNATURES);
                if (pi == null) return null;
                sigs = pi.signatures;
            }
            if (sigs == null || sigs.length == 0) return null;
            List<String> out = new ArrayList<String>();
            for (Signature s : sigs) out.add(UpdaterRules.certSha256(s.toByteArray()));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static String installerOfRecord(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            if (Build.VERSION.SDK_INT >= 30) return pm.getInstallSourceInfo(ctx.getPackageName()).getInstallingPackageName();
            return pm.getInstallerPackageName(ctx.getPackageName());
        } catch (Exception e) {
            return null;
        }
    }

    /** Android 14+: the package that owns this app's updates (Task 47i), or null when none is set or Android is older. */
    private static String updateOwner(Context ctx) {
        if (Build.VERSION.SDK_INT < 34) return null;
        try {
            return ctx.getPackageManager().getInstallSourceInfo(ctx.getPackageName()).getUpdateOwnerPackageName();
        } catch (Exception e) {
            return null;
        }
    }

    private static String storePackages(Context ctx) {
        String configured = metaString(ctx, META_STORE_PACKAGES);
        return configured == null || configured.isEmpty() ? DEFAULT_STORE_PACKAGES : configured;
    }

    private static String metaString(Context ctx, String key) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(ctx.getPackageName(), PackageManager.GET_META_DATA);
            if (ai.metaData == null) return null;
            String v = ai.metaData.getString(key);
            return v == null ? null : v.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String label(Context ctx) {
        try {
            CharSequence l = ctx.getPackageManager().getApplicationLabel(ctx.getApplicationInfo());
            return l == null ? ctx.getPackageName() : l.toString();
        } catch (Exception e) {
            return ctx.getPackageName();
        }
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) return t.length() > 140 ? t.substring(0, 140) + "..." : t;
        }
        return "";
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // ---- network ------------------------------------------------------------------------------------------

    /** GET of a small JSON answer over https. Null for a 404; an IOException for anything else that is not 200. */
    private static String httpGetSmall(String url) throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!UpdaterRules.isHttps(current)) throw new IOException("not an https address");
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(CONNECT_TIMEOUT);
                c.setReadTimeout(READ_TIMEOUT);
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code == 404) return null;
                if (code == 301 || code == 302 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("a redirect without a location");
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                if (code != 200) throw new IOException("the server answered " + code);
                InputStream in = c.getInputStream();
                try {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                        if (bos.size() > MAX_ANSWER_BYTES) throw new IOException("the answer is too large");
                    }
                    return bos.toString("UTF-8");
                } finally {
                    in.close();
                }
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    // ---- notifications ------------------------------------------------------------------------------------

    private static int immutableFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private static boolean canNotify(Context ctx) {
        return Build.VERSION.SDK_INT < 33
            || ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }

    private static void cancelNotification(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(UpdaterRules.notificationId(ctx.getPackageName()));
    }

    /**
     * One stable notification id per package, so each state replaces the last. [percent] below 0 means no bar;
     * [ongoing] keeps it from being swiped away while work is running.
     */
    @SuppressWarnings("deprecation")
    private static void show(Context ctx, String title, String text, PendingIntent tap, boolean ongoing, int percent) {
        if (!canNotify(ctx)) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setSmallIcon(ongoing ? android.R.drawable.stat_sys_download : android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing);
        if (percent >= 0) b.setProgress(100, percent, false);
        if (tap != null) b.setContentIntent(tap);
        nm.notify(UpdaterRules.notificationId(ctx.getPackageName()), b.build());
    }
}
