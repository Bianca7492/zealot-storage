package com.zealot.updater;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
 * Task 47j: the branded in-app update dialog the injected updater shows inside a host app, the same
 * "in-app update" pattern Play Core's {@code AppUpdateManager} provides, delivered by injection so no app
 * code changes and no third-party SDK in the app. It is the counterpart of {@code ZealotConsentOverlay}
 * (same dependency-free, code-only, view-injected construction and system light/dark palette).
 *
 * What it shows: the store's own branding -- the tenant display name and a brand mark -- a title naming the
 * host app ("Update &lt;app&gt;?"), the offered version, and the first line of its changelog. Branding is read
 * from the manifest meta-data the Zealot patcher writes for the tenant, so a white-label build shows ITS
 * name, never "Appstore" (the first-party default).
 *
 * What Update does: hands off to the store app that published this build, opening the app's own details page
 * ({@code <scheme>://open/<package>}) where the person updates it -- the same cross-app handoff Play Core
 * does when it launches Play Store on the app's page. If that store app is not installed the button falls
 * back to the injected updater's own download-and-install, so the update is never a dead end. Later dismisses
 * the sheet; the offer is remembered so the dialog does not nag (once per version).
 */
final class UpdateSheet {

    static final String TAG = "zealot_update_sheet";

    private UpdateSheet() {}

    /**
     * Attaches the sheet to [activity]'s decor view when an update offer is pending and the sheet has not
     * been shown for this version. Returns true when the sheet is on screen (or was already), false when it
     * could not be attached. Must run on the main thread.
     */
    static boolean attach(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        final Context context = activity.getApplicationContext();
        if (!ZealotUpdater.hasPendingOffer(context)) return false;

        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (!(decor instanceof ViewGroup)) return false;
        ViewGroup root = (ViewGroup) decor;
        if (root.findViewWithTag(TAG) != null) return true;   // already on this Activity

        final View sheetRoot = build(activity, context);
        sheetRoot.setTag(TAG);
        root.addView(sheetRoot, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Remembered only once it is really on screen, so a failed attach retries on the next resume.
        ZealotUpdater.markSheetShown(context);
        return true;
    }

    /** Builds the dim backdrop + centred card. Dependency-free: plain widgets, code dimensions. */
    private static View build(final Activity activity, final Context context) {
        float d = activity.getResources().getDisplayMetrics().density;
        final int pad = (int) (24 * d);

        // Follow the SYSTEM theme, like the consent sheet: light mode shows the light palette, dark mode
        // the dark one, independent of the host app's own theme.
        final boolean dark = (activity.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        final int scrim    = dark ? Color.parseColor("#99000000") : Color.parseColor("#66000000");
        final int sheetBg  = dark ? Color.parseColor("#FF1B1B1F") : Color.parseColor("#FFFFFFFF");
        final int onSheet  = dark ? Color.parseColor("#FFFFFFFF") : Color.parseColor("#FF1B1B1F");
        final int onSheet2 = dark ? Color.parseColor("#FFB9BAC4") : Color.parseColor("#FF44464F");
        final int accent   = Color.parseColor("#FFD0BCFF");

        final String appLabel = ZealotUpdater.appLabel(context);
        final String storeName = ZealotUpdater.storeName(context);
        final String versionName = ZealotUpdater.pendingVersionName(context);
        final String changelog = ZealotUpdater.pendingChangelogLine(context);

        final FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(scrim);
        root.setPadding(pad, pad, pad, pad);
        // Swallow every touch aimed at the app behind the sheet: the app stays visible, not usable.
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return true; }
        });

        final LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, (int) (16 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(sheetBg);
        bg.setCornerRadius(24 * d);
        card.setBackground(bg);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        card.setLayoutParams(cardLp);

        // ---- brand row: a filled circular badge with the store's initial, then its name ----
        LinearLayout brandRow = new LinearLayout(activity);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(activity);
        String initial = storeName.isEmpty() ? "Z" : storeName.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        badge.setText(initial);
        badge.setTextColor(Color.parseColor("#FF1B1B1F"));
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        badge.setTypeface(badge.getTypeface(), Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(accent);
        badge.setBackground(badgeBg);
        int badgeSize = (int) (32 * d);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(badgeSize, badgeSize);
        badge.setLayoutParams(badgeLp);
        brandRow.addView(badge);

        TextView brand = new TextView(activity);
        brand.setText(storeName);
        brand.setTextColor(onSheet);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        brand.setTypeface(brand.getTypeface(), Typeface.BOLD);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brandLp.leftMargin = (int) (10 * d);
        brand.setLayoutParams(brandLp);
        brandRow.addView(brand);
        card.addView(brandRow);

        // ---- title ----
        TextView title = new TextView(activity);
        title.setText("Update " + appLabel + "?");
        title.setTextColor(onSheet);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = (int) (16 * d);
        title.setLayoutParams(titleLp);
        card.addView(title);

        // ---- version + changelog ----
        StringBuilder bodyText = new StringBuilder();
        if (!versionName.isEmpty()) {
            bodyText.append("Version ").append(versionName).append(" is available.");
        } else {
            bodyText.append("A new version is available.");
        }
        if (!changelog.isEmpty()) bodyText.append("\n").append(changelog);
        TextView body = new TextView(activity);
        body.setText(bodyText.toString());
        body.setTextColor(onSheet2);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = (int) (8 * d);
        body.setLayoutParams(bodyLp);
        card.addView(body);

        // ---- actions ----
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = (int) (20 * d);
        actions.setLayoutParams(actionsLp);

        // Later is the single non-affirmative action: it just closes, no state change beyond the
        // already-recorded "shown for this version" latch.
        Button later = new Button(activity);
        later.setText("Later");
        later.setAllCaps(false);
        later.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(root, card); }
        });
        actions.addView(later);

        // Update hands off to the store app's own details page; if that app is absent it falls back to this
        // updater's download-and-install so the tap always does something useful.
        Button update = new Button(activity);
        update.setText("Update");
        update.setAllCaps(false);
        LinearLayout.LayoutParams updateLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        updateLp.leftMargin = (int) (12 * d);
        update.setLayoutParams(updateLp);
        update.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean opened = openStoreListing(context);
                if (!opened) ZealotUpdater.startDownload(context);
                dismiss(root, card);
            }
        });
        actions.addView(update);

        card.addView(actions);
        root.addView(card);

        // A short fade-and-rise, matching the consent sheet's motion.
        card.setAlpha(0f);
        card.setTranslationY(24 * d);
        card.post(new Runnable() {
            @Override public void run() {
                card.animate().alpha(1f).translationY(0f).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
            }
        });
        return root;
    }

    /**
     * Opens the publishing store app on this app's details page. {@code setPackage} names the tenant's store
     * explicitly, so the correct white-label store opens (and not another app that happens to register the
     * same scheme). Returns false when no store app is installed to handle it.
     */
    private static boolean openStoreListing(Context context) {
        try {
            Intent open = new Intent(Intent.ACTION_VIEW,
                Uri.parse(ZealotUpdater.storeScheme(context) + "://open/" + context.getPackageName()));
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String storePkg = ZealotUpdater.storePackageToOpen(context);
            if (storePkg != null && !storePkg.isEmpty()) open.setPackage(storePkg);
            context.startActivity(open);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Fades the card out, then detaches the whole overlay from the host's window. */
    private static void dismiss(final View root, final View card) {
        card.animate().alpha(0f).setDuration(160)
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
