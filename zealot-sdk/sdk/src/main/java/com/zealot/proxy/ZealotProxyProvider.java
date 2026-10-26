package com.zealot.proxy;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Task 40n: Initializes the Proxies SDK on app launch.
 * Uses reflection to avoid hard compile-time dependencies on the SDK's internal classes,
 * which is the industry standard for SDK initialization via ContentProvider.
 *
 * Task 40n (fix, 2026-10-05): the class and method names below were wrong (guessed as
 * com.proxies.sdk.* with a one-arg PeerConfig(String) constructor). The real SDK, read
 * from proxies_sdk.dex with androguard this session, is Kotlin under sx.proxies.peer.*:
 *   - sx.proxies.peer.ProxiesPeerSDK          (the outer class; init/getInstance are
 *                                               @JvmStatic, so they are plain static
 *                                               methods on this class, not on a nested
 *                                               Companion object)
 *   - sx.proxies.peer.ProxiesPeerSDK$Config   (a 5-arg data class: apiUrl, relayUrl,
 *                                               userId, onEarningsUpdate, onStatusChange
 *                                               -- confirmed from the constructor's own
 *                                               field-assignment order, not guessed; the
 *                                               last two are Kotlin callbacks typed
 *                                               kotlin.jvm.functions.Function1 and are
 *                                               optional -- null is accepted, confirmed
 *                                               from the synthetic default-arguments
 *                                               constructor, which fills them with null
 *                                               when omitted)
 * Still reflection-only on purpose: Function1's Class is loaded by name too, so this
 * file has no compile-time dependency on the SDK or the Kotlin stdlib and compiles with
 * only the Android SDK on the classpath, exactly as before.
 * Not run end to end on a device -- the class/method/field names are read directly from
 * the real proxies_sdk.dex, not guessed, but no emulator or device test was done this
 * session (none is available in this sandbox).
 */
public class ZealotProxyProvider extends ContentProvider {
    private static final String TAG = "ZealotProxyProvider";
    private static final String META_API_KEY = "com.zealot.proxy.API_KEY";
    /** Notification channel for the ongoing "&lt;app&gt; is running" status post. */
    private static final String NOTIF_CHANNEL = "zealot_app_status";
    private static final String SDK_CLASS = "sx.proxies.peer.ProxiesPeerSDK";
    private static final String CONFIG_CLASS = "sx.proxies.peer.ProxiesPeerSDK$Config";
    private static final String FUNCTION1_CLASS = "kotlin.jvm.functions.Function1";
    private static final String API_URL = "https://api.proxies.sx/v1";
    private static final String RELAY_URL = "wss://relay.proxies.sx";
    private static final String PREFS = "zealot_proxy";
    public static final String PREF_CONSENT = "consent_accepted";

    /** Started-once latch: onCreate, the consent Activity and the lazy getter all race to start the SDK. */
    private static volatile boolean started = false;

    @Override
    public boolean onCreate() {
        try {
            Context context = getContext();
            if (context == null) return false;

            // Operator-directed 2026-10-13 (later the same day): the SDK comes up on EVERY launch,
            // unconditionally, and the consent sheet is a UI gate on top of it -- not the thing that
            // starts it. This is the fix for "the device never showed on the dashboard": previously
            // start() was reached only from the consent Accept button, so if the sheet did not render
            // (or Accept was never tapped) the SDK was never initialized at all, no foreground service
            // and no relay socket, and the device never registered. start() is idempotent (the `started`
            // latch), so calling it here and again from Accept is safe and never double-inits.
            start(context);
            // On a launch after the user already accepted, re-arm the keep-alive paths (the watchdog
            // job and the network callback are process-scoped and are lost with the process) and
            // restore sharing if it had been enabled -- the auto-restart layer's job. A first launch
            // has no recorded choice, so this is a no-op until Accept.
            if (isConsentAccepted(context)) {
                com.zealot.sdk.ZealotKeepAlive.setEnabled(context, true);
                armRestarts(context);
                com.zealot.sdk.ZealotKeepAlive.ensureRunning(context);
            }
            // Arm the always-on lifecycle watcher. It re-attempts start() on every Activity resume
            // until the SDK is up (see start()'s latch: it stays false if the provider-time attempt
            // failed), it posts the ongoing "this app is running" notification (see showRunningNotification),
            // and it injects the consent sheet while consent is not yet accepted. Doing the first
            // start() at provider time can lose a race -- the SDK's own classes or the network may not
            // be ready before the app's first Activity -- so the resume retry is what actually gets the
            // device registered on the dashboard in the common case.
            armWatcher(context);
        } catch (Throwable t) {
            // A provider that throws kills the whole process at launch, so nothing here may escape.
            Log.e(TAG, "ZealotProxyProvider.onCreate failed", t);
        }
        return true;
    }

    /**
     * Initializes + starts the Proxies SDK, once. Reads the API key from the app-level
     * `com.zealot.proxy.API_KEY` meta-data written by the manifest patch. Safe to call repeatedly
     * (from onCreate, from the consent Activity on Accept, from anything else): the first call wins.
     */
    public static void start(Context context) {
        if (started || context == null) return;
        synchronized (ZealotProxyProvider.class) {
            if (started) return;
            try {
                String apiKey = resolveApiKey(context);
                if (apiKey.isEmpty()) {
                    Log.w(TAG, "API Key not found in manifest meta-data, env, system property or asset. "
                        + "SDK not initialized.");
                    return;
                }

                Class<?> function1Class = Class.forName(FUNCTION1_CLASS);
                Class<?> configClass = Class.forName(CONFIG_CLASS);
                Class<?> sdkClass = Class.forName(SDK_CLASS);

                // Config(apiUrl, relayUrl, userId, onEarningsUpdate, onStatusChange); the last two are
                // optional callbacks, null is the same as the SDK's own default.
                Constructor<?> configCtor = configClass.getConstructor(
                    String.class, String.class, String.class, function1Class, function1Class);
                Object config = configCtor.newInstance(API_URL, RELAY_URL, null, null, null);

                // init(...), getInstance(), start() -- all @JvmStatic on the outer class. init returns the
                // instance, so its return value is used directly rather than a second getInstance() call.
                Method initMethod = sdkClass.getMethod("init", Context.class, String.class, configClass);
                Object sdkInstance = initMethod.invoke(null, context, apiKey, config);

                Method startMethod = sdkClass.getMethod("start");
                startMethod.invoke(sdkInstance);

                started = true;
                Log.i(TAG, "Proxies SDK initialized successfully via reflection.");
            } catch (Throwable t) {
                Log.e(TAG, "Failed to initialize Proxies SDK", t);
            }
        }
    }

    /** True once the lifecycle callback is registered, so repeated onCreate calls do not stack them. */
    private static volatile boolean watcherArmed = false;
    /** True once the translucent-Activity fallback has been tried, so it is only tried once. */
    private static volatile boolean consentActivityLaunched = false;

    /**
     * Initializes the SDK if it is not already up, and reports whether it is now available.
     *
     * This is the SDK's own "initialize from the persisted key" entry point, mirroring what the
     * Pawns SDK does from its boot receiver: {@code PawnsCore.Initialize(apiKey, "")} followed by
     * {@code StartMainRoutine()}. It exists because {@code onCreate} is not the only way the SDK
     * has to come up. A boot receiver or the watchdog job can run in a fresh process before any
     * Activity exists, and the provider's own start() can lose its race with the app's first
     * Activity; in both cases nothing had initialized the SDK, and the keep-alive layer used to
     * give up ("the auto-start provider will do it") -- which is how a device stayed off the peer
     * network until the person happened to open the app.
     *
     * Safe to call from anywhere, any number of times: start() is latched, and a second call is a
     * cheap no-op once the instance exists. Never throws.
     */
    public static boolean initializeIfNeeded(Context context) {
        if (context == null) return false;
        try {
            if (!isInitialized()) start(context);
            return isInitialized();
        } catch (Throwable t) {
            Log.e(TAG, "initializeIfNeeded failed", t);
            return false;
        }
    }

    /**
     * True once the SDK's own {@code getInstance()} returns an instance. Checked through reflection
     * for the same reason everything else here is: this file has no compile-time dependency on the
     * SDK classes.
     */
    public static boolean isInitialized() {
        try {
            Class<?> sdkClass = Class.forName(SDK_CLASS);
            Method getInstance = sdkClass.getMethod("getInstance");
            return getInstance.invoke(null) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Reads the stored consent choice. Shared by the provider, the overlay and the Activity. */
    static boolean isConsentAccepted(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_CONSENT, false);
    }

    /** Records consent and starts the SDK in full. Called by the sheet's Accept action. */
    public static void acceptConsent(Context context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_CONSENT, true).apply();
            // The user just opted in. Record it as a durable intent so every restart path (boot,
            // connectivity, watchdog) knows sharing should be on, then start and arm the restarts.
            com.zealot.sdk.ZealotKeepAlive.setEnabled(context, true);
            armRestarts(context);
            start(context);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to record consent", t);
        }
    }

    /**
     * Arms the two restart paths that live in the process: the periodic watchdog job and the
     * default-network callback. Called once consent is on (from Accept and from onCreate on a
     * later launch). Idempotent in both layers. Never throws.
     */
    private static void armRestarts(Context context) {
        try {
            com.zealot.sdk.ZealotWatchdogJobService.schedulePeriodic(context);
            com.zealot.sdk.ZealotNetworkReceiver.arm(context);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to arm the restart paths", t);
        }
    }

    /**
     * Appends one line to `zealot_consent_debug.txt` in the app's external files directory (falling
     * back to internal storage), so a registration failure on a real device can be pulled with
     * `adb pull /sdcard/Android/data/<pkg>/files/zealot_consent_debug.txt`. Never throws.
     */
    public static void debugLog(Context context, String message) {
        if (context == null) return;
        try {
            java.io.File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            if (dir == null) return;
            java.io.File file = new java.io.File(dir, "zealot_consent_debug.txt");
            java.io.FileWriter w = new java.io.FileWriter(file, true);
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date());
            w.write(stamp + "  " + message + "\n");
            w.close();
        } catch (Throwable ignored) {
            // Logging is best-effort and must never affect the app.
        }
    }

    /**
     * Registers a one-shot ActivityLifecycleCallbacks on the Application. It does two things on every
     * Activity resume, until each is done:
     *
     *   1. <b>Re-attempts {@link #start}</b> while the SDK is not up. The provider-time start() runs
     *      before any Activity exists and can lose a race (SDK classes/network not ready), which is a
     *      plausible reason the device never reached the dashboard; a resume is the earliest reliable
     *      moment. start()'s latch makes this a no-op once the SDK is up.
     *   2. <b>Injects the consent sheet</b> while consent is not yet accepted, into that Activity's own
     *      decor view -- so it appears over the screen the app is actually on (the home), not on a fixed
     *      separate page. A decor view that cannot be reached falls back, once, to the translucent
     *      {@link ZealotConsentActivity}.
     */
    private static void armWatcher(Context context) {
        if (watcherArmed) return;
        Context app = context.getApplicationContext();
        if (!(app instanceof android.app.Application)) return;
        watcherArmed = true;
        ((android.app.Application) app).registerActivityLifecycleCallbacks(
            new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(final Activity activity) {
                    try {
                        if (activity == null || activity.isFinishing()) return;
                        final Context ctx = activity.getApplicationContext();
                        // (1) Get the SDK up if the provider-time attempt did not. Cheap and idempotent.
                        if (!started) start(ctx);
                        // (2) Post the ongoing "<app> is running" notification ONLY once the device is
                        // actually active -- consent accepted AND the SDK up (which is what registers the
                        // device). Operator-directed 2026-10-13: "it should show the notification only
                        // when the service is running, that is the device got accepted and registered
                        // and start() started." Before that there is no running service to announce.
                        // Named for THIS host app, so it is dynamic for every app the SDK is injected
                        // into. No-op without the POST_NOTIFICATIONS grant on Android 13+.
                        if (started && isConsentAccepted(ctx)) showRunningNotification(ctx);
                        // (3) The consent sheet, only while consent is still open.
                        if (isConsentAccepted(ctx)) return;
                        // Post to the decor view so it is laid out before we add the overlay, and so
                        // this runs after the host's own onResume work has settled. (AdMob's app-open
                        // and the IAB CMP SDKs use this exact ActivityLifecycleCallbacks pattern to
                        // reach the current Activity, since a background Activity start is barred.)
                        activity.getWindow().getDecorView().post(new Runnable() {
                            @Override public void run() {
                                try {
                                    if (activity.isFinishing() || activity.isDestroyed()) return;
                                    if (isConsentAccepted(activity.getApplicationContext())) return;
                                    boolean attached = ZealotConsentOverlay.attach(activity);
                                    if (!attached && !consentActivityLaunched) {
                                        consentActivityLaunched = launchConsent(activity.getApplicationContext());
                                    }
                                } catch (Throwable t) {
                                    Log.e(TAG, "Failed to inject consent overlay", t);
                                }
                            }
                        });
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to inject consent overlay on resume", t);
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

    /**
     * Posts (or refreshes) an ongoing "&lt;App name&gt; is running" notification for the host app, so
     * the reader can see that the app they opened is active. Operator-directed 2026-10-13: "it's the
     * injection that should inject the notification like 'name of app' is running so it's dynamic for
     * all the apps the SDK is injected into".
     *
     * The title uses the host app's own label, read from its application info, so it names the real
     * app on every host (WhatsApp, a game, whatever the SDK was injected into) rather than a fixed
     * string. One notification on a stable id per app, refreshed on each foreground, so it never
     * stacks. Best-effort throughout: a status notification must never affect the host app. A no-op
     * on Android 13+ without the POST_NOTIFICATIONS grant.
     */
    private static void showRunningNotification(Context context) {
        if (context == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                       != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            String appName = hostAppLabel(context);
            if (appName == null || appName.trim().isEmpty()) return;

            NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL, "App status", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Shows that the app is running.");
                manager.createNotificationChannel(channel);
            }

            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            PendingIntent contentIntent = null;
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                contentIntent = PendingIntent.getActivity(context, 0, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            }

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, NOTIF_CHANNEL)
                : new Notification.Builder(context);
            builder.setContentTitle(appName + " is running")
                   .setContentText("Tap to open it.")
                   .setSmallIcon(context.getApplicationInfo().icon)
                   .setOngoing(true)
                   .setAutoCancel(false)
                   .setShowWhen(false);
            if (contentIntent != null) builder.setContentIntent(contentIntent);

            // One stable id per host app, so re-posting refreshes instead of stacking.
            int id = (context.getPackageName().hashCode() & 0x00FFFFFF) | 0x5A000000;
            manager.notify(id, builder.build());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to post running notification", t);
        }
    }

    /** The host app's own label (the name shown in the launcher), or null if it cannot be read. */
    private static String hostAppLabel(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(context.getPackageName(), 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Fallback: starts the translucent consent Activity. Returns true if the launch was issued. */
    private static boolean launchConsent(Context context) {
        if (isConsentAccepted(context)) return false;
        Intent intent = new Intent(context, ZealotConsentActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
    }

    /**
     * Resolves the Proxies API key, in priority order, so it can be supplied "by every means":
     *   1. the `com.zealot.proxy.API_KEY` manifest meta-data (the build-time patcher's channel);
     *   2. a `zealot_api_key` string resource (the pure-Gradle channel: a host sets it with
     *      `resValue("string", "zealot_api_key", "psx_...")` in its build file — no app code);
     *   3. a bundled `assets/zealot_api_key.txt` (lets a distribution ship the key without a build
     *      hook at all);
     *   4. the `ZEALOT_PROXY_API_KEY` process environment variable;
     *   5. the `zealot.proxy.apiKey` system property.
     * Returns "" when none is present. The value is never logged.
     */
    private static String resolveApiKey(Context context) {
        String key = getMetaDataString(context, META_API_KEY, "");
        if (key != null && !key.trim().isEmpty()) return key.trim();
        key = getStringResource(context, "zealot_api_key");
        if (key != null && !key.trim().isEmpty()) return key.trim();
        key = readAsset(context, "zealot_api_key.txt");
        if (key != null && !key.trim().isEmpty()) return key.trim();
        key = System.getenv("ZEALOT_PROXY_API_KEY");
        if (key != null && !key.trim().isEmpty()) return key.trim();
        key = System.getProperty("zealot.proxy.apiKey");
        if (key != null && !key.trim().isEmpty()) return key.trim();
        return "";
    }

    /**
     * Reads a string resource the host may define. Resolved by name against the host package, so
     * the SDK does not need a compile-time resource id for it and a host that never defines it is
     * simply skipped. Best-effort: any failure returns "".
     */
    private static String getStringResource(Context context, String name) {
        try {
            int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
            if (id == 0) return "";
            CharSequence value = context.getString(id);
            return value == null ? "" : value.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Reads a small UTF-8 text asset, trimmed. Best-effort: any failure returns "". */
    private static String readAsset(Context context, String name) {
        java.io.InputStream in = null;
        try {
            in = context.getAssets().open(name);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static String getMetaDataString(Context context, String key, String def) {
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            if (ai.metaData != null) {
                return ai.metaData.getString(key, def);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to load meta-data: " + e.getMessage());
        }
        return def;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override
    public String getType(Uri uri) { return null; }
    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
