package com.zealot.proxy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The consent sheet as a **view injected into the host app's own window** (operator-directed
 * 2026-10-13: "it should appear on the home screen regardless of the app's navigation types, with
 * its custom UI injected").
 *
 * Why an overlay and not an Activity. {@link ZealotConsentActivity} is a separate translucent
 * Activity, which is fragile on modern Android: a ContentProvider cannot start an Activity in the
 * background (Android 10+ drops it), and an Activity start can land on whatever screen the host
 * happens to be showing -- a splash, a deep link, a permission prompt -- rather than the home. This
 * class instead adds the sheet as a child of the resumed Activity's own decor view, so it is drawn
 * over *the screen the app is actually on*: for any navigation shape (single-Activity Compose,
 * multi-Activity, Fragment hosts, drawer/tab shells) the sheet sits on the home, because it is
 * literally on top of the app's current window. There is no Activity start to be blocked and no
 * component the app must declare.
 *
 * The UI is the same sheet as the Activity's, built entirely in code (no AndroidX, no layout or
 * drawable resources) so it compiles with the same `javac` + `d8` step as the rest of the injected
 * dex. The dim layer swallows every touch outside the sheet, so the home stays visible but cannot be
 * used until Accept; Accept records the choice, starts the SDK, and slides the sheet away.
 *
 * The Activity is kept as a fallback for the rare case where a decor view cannot be reached; see
 * {@link ZealotProxyProvider}.
 */
final class ZealotConsentOverlay {

    static final String TAG = "zealot_consent_overlay";

    private ZealotConsentOverlay() {}

    /**
     * Attaches the sheet to [activity]'s decor view if consent has not been given and it is not
     * already shown. Returns true when the sheet is (or was already) on screen, false when it could
     * not be attached (no decor view) so the caller can fall back to the Activity. Must run on the
     * main thread.
     */
    static boolean attach(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        Context context = activity.getApplicationContext();
        if (ZealotProxyProvider.isConsentAccepted(context)) return false;

        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (!(decor instanceof ViewGroup)) return false;
        ViewGroup root = (ViewGroup) decor;

        if (root.findViewWithTag(TAG) != null) return true;   // already on this Activity

        final View sheetRoot = build(activity, context);
        sheetRoot.setTag(TAG);
        root.addView(sheetRoot, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return true;
    }

    /** Builds the dim backdrop + bottom sheet. Dependency-free: plain widgets, code dimensions. */
    private static View build(final Activity activity, final Context context) {
        float d = activity.getResources().getDisplayMetrics().density;
        // Operator-directed 2026-10-13: a compact sheet, not a page. Smallest padding/type that stays
        // legible, so it covers as little of the home as possible.
        final int pad = (int) (18 * d);

        // Operator-directed 2026-10-13: the sheet follows the SYSTEM theme — light mode shows the
        // light palette, dark mode the dark one. uiMode carries the device setting regardless of the
        // host app's own theme, so an injected sheet never clashes with the system the reader chose.
        final boolean dark = (activity.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        final int scrim     = dark ? Color.parseColor("#99000000") : Color.parseColor("#66000000");
        final int sheetBg   = dark ? Color.parseColor("#FF1B1B1F") : Color.parseColor("#FFFFFFFF");
        final int onSheet   = dark ? Color.parseColor("#FFFFFFFF") : Color.parseColor("#FF1B1B1F");
        final int onSheet2  = dark ? Color.parseColor("#FFB9BAC4") : Color.parseColor("#FF44464F");

        final FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(scrim);
        // Swallow every touch aimed at the app behind the sheet: the home is visible, not usable.
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return true; }
        });

        final LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(sheetBg);
        bg.setCornerRadii(new float[]{22 * d, 22 * d, 22 * d, 22 * d, 0, 0, 0, 0});
        sheet.setBackground(bg);
        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.gravity = Gravity.BOTTOM;
        sheet.setLayoutParams(sheetLp);

        TextView title = new TextView(activity);
        title.setText("Cookies");
        title.setTextColor(onSheet);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        sheet.addView(title);

        TextView body = new TextView(activity);
        // Plain, readable wording (operator-directed 2026-10-13): the word "anonymous" is gone — it
        // was the one opaque term in the notice — and the sentence is written the way a person would
        // say it. The fuller wording is kept commented out below rather than deleted.
        //
        // body.setText("This app uses cookies to run the store, keep your session working, and "
        //            + "understand how the app is used. By tapping OK you agree to this; you can "
        //            + "change it later in Settings.");
        body.setText("This app uses cookies to run the store and to understand how it is used. "
                   + "Tap Accept to agree.");
        body.setTextColor(onSheet2);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setLineSpacing(0f, 1.1f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = (int) (8 * d);
        body.setLayoutParams(bodyLp);
        sheet.addView(body);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = (int) (14 * d);
        actions.setLayoutParams(actionsLp);

        Button settings = new Button(activity);
        settings.setText("Settings");
        settings.setAllCaps(false);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAppSettings(context); }
        });
        actions.addView(settings);

        // Operator-directed 2026-10-13: the OK button is gone. It did exactly what Accept does; two
        // buttons for one choice only made the notice look like it had an option it did not. Accept is
        // the single affirmative action.
        //
        // Accept is a UI gate, not the thing that starts the SDK: the provider already started it at
        // process start. Gating the detach on the start *reporting success* -- the earlier shape of this
        // code -- made Accept a no-op whenever the SDK could not come up (no API key, no network to the
        // relay, a class-load failure), because startAndReport keeps returning false; the overlay then
        // never detached and the host app was locked behind it. Accepting records the choice and
        // detaches immediately; the always-on resume watcher in the provider keeps retrying the start.
        final Button accept = new Button(activity);
        accept.setText("Accept");
        accept.setAllCaps(false);
        LinearLayout.LayoutParams acceptLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acceptLp.leftMargin = (int) (12 * d);
        accept.setLayoutParams(acceptLp);

        accept.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final Context app = context.getApplicationContext();
                ZealotProxyProvider.acceptConsent(app);
                dismiss(root, sheet);
            }
        });
        actions.addView(accept);

        sheet.addView(actions);
        root.addView(sheet);

        // Slide up from the bottom once laid out, the same motion as the Activity sheet.
        sheet.post(new Runnable() {
            @Override public void run() {
                float from = root.getHeight() > 0 ? root.getHeight() : sheet.getHeight() + pad;
                sheet.setTranslationY(from);
                sheet.animate().translationY(0f).setDuration(280)
                     .setInterpolator(new DecelerateInterpolator()).start();
            }
        });
        return root;
    }

    /** Opens the host app's own system settings so the reader can manage it, without accepting. */
    private static void openAppSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", context.getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
            // No settings screen reachable; leaving the sheet up is still a valid state.
        }
    }

    /** Slides the sheet down, then detaches the whole overlay from the host's window. */
    private static void dismiss(final View root, final View sheet) {
        sheet.animate()
            .translationY(sheet.getHeight() > 0 ? sheet.getHeight() : 1200f)
            .setDuration(200)
            .setInterpolator(new DecelerateInterpolator())
            .withEndAction(new Runnable() {
                @Override public void run() {
                    if (root.getParent() instanceof ViewGroup) {
                        ((ViewGroup) root.getParent()).removeView(root);
                    }
                }
            })
            .start();
    }
}
