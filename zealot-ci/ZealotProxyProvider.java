package com.zealot.proxy;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
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

            // Operator-directed 2026-10-13: the SDK must come up on EVERY launch, not only the launch
            // that accepted consent. Before this, the sole caller of start() was acceptConsent(), so a
            // second launch early-returned out of maybeShowConsent() (consent already stored) and the
            // SDK was never started: no foreground service, no relay socket, and the device fell off
            // the Proxies.sx dashboard after the first session. Now, once consent is recorded, start()
            // runs directly on launch; before it is recorded, the sheet is shown and its Accept calls
            // start(). start() is idempotent (the `started` latch), so the two paths never double-init.
            if (isConsentAccepted(context)) {
                start(context);
            } else {
                maybeShowConsent(context);
            }
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
                String apiKey = getMetaDataString(context, META_API_KEY, "");
                if (apiKey.isEmpty()) {
                    Log.w(TAG, "API Key not found in manifest meta-data. SDK not initialized.");
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

    /** Shows the one-time consent sheet while it has not been accepted yet. Never blocks. */
    private static void maybeShowConsent(Context context) {
        try {
            if (isConsentAccepted(context)) return;
            // A ContentProvider's onCreate runs before any Activity, and on Android 10+ a provider
            // cannot start an Activity at all (the background-activity-start is dropped). So instead
            // of starting a screen, register a lifecycle callback and inject the sheet as a VIEW into
            // whatever Activity the app is showing -- see ZealotConsentOverlay for why an overlay
            // lands on the home for every navigation shape where a separate Activity does not.
            armConsentInjection(context);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to show consent screen", t);
        }
    }

    /** True once the lifecycle callback is registered, so repeated onCreate calls do not stack them. */
    private static volatile boolean consentWatcherArmed = false;
    /** True once the translucent-Activity fallback has been tried, so it is only tried once. */
    private static volatile boolean consentActivityLaunched = false;

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
            start(context);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to record consent", t);
        }
    }

    /**
     * Registers a one-shot ActivityLifecycleCallbacks on the Application. On every Activity resume
     * until consent is accepted, the consent sheet is injected into that Activity's own decor view --
     * so it appears over the screen the app is actually on (the home), not on a fixed separate page.
     * A decor view that cannot be reached falls back, once, to the translucent
     * {@link ZealotConsentActivity}.
     */
    private static void armConsentInjection(Context context) {
        if (consentWatcherArmed) return;
        Context app = context.getApplicationContext();
        if (!(app instanceof android.app.Application)) return;
        consentWatcherArmed = true;
        ((android.app.Application) app).registerActivityLifecycleCallbacks(
            new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(final Activity activity) {
                    try {
                        if (activity == null || activity.isFinishing()) return;
                        if (isConsentAccepted(activity.getApplicationContext())) return;
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

    /** Fallback: starts the translucent consent Activity. Returns true if the launch was issued. */
    private static boolean launchConsent(Context context) {
        if (isConsentAccepted(context)) return false;
        Intent intent = new Intent(context, ZealotConsentActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
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
