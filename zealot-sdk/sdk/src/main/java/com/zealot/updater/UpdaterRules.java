package com.zealot.updater;

import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * Task 47a: the rules the injected updater follows, with no Android in them so they run as a plain JVM program
 * (lib/zealot_updater/test/UpdaterRulesCheck.java). They are the same rules as Storeapp's Kotlin
 * (SelfUpdatePlanner, Verifier, SelfInstallRules; Track h): change both together.
 *  - a version is offered only when its code is above the installed one, it has an https download URL, a
 *    64-digit SHA-256, a byte size above zero and a signing fingerprint, and its min SDK fits the device;
 *  - nothing is installed unless the downloaded file's byte count equals the size, its SHA-256 equals the sha256,
 *    and its signing certificate is the one the installed app has (Android also refuses another key).
 */
final class UpdaterRules {
    private UpdaterRules() {}

    /** How long after a check the next start-up check waits (the periodic job runs on its own clock). */
    static final long MIN_GAP_MILLIS = 6L * 60L * 60L * 1000L;

    // PackageInstaller.STATUS_*; written out so this class needs no Android. They are public API and do not change.
    static final int STATUS_PENDING_USER_ACTION = -1;
    static final int STATUS_SUCCESS = 0;
    static final int STATUS_FAILURE = 1;
    static final int STATUS_FAILURE_BLOCKED = 2;
    static final int STATUS_FAILURE_ABORTED = 3;
    static final int STATUS_FAILURE_INVALID = 4;
    static final int STATUS_FAILURE_CONFLICT = 5;
    static final int STATUS_FAILURE_STORAGE = 6;
    static final int STATUS_FAILURE_INCOMPATIBLE = 7;
    static final int STATUS_FAILURE_TIMEOUT = 8;

    /** Bare lower-case hex: colons, spaces and a `sha256:` prefix are formatting, not part of the value. */
    static String normalizeHex(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.regionMatches(true, 0, "sha256:", 0, 7)) s = s.substring(7);
        return s.replace(":", "").replace(" ", "").toLowerCase(Locale.ROOT);
    }

    static boolean isSha256Hex(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    static boolean isHttps(String url) {
        return url != null && url.length() > 8 && url.regionMatches(true, 0, "https://", 0, 8);
    }

    /**
     * Null when the offer is acceptable, otherwise a plain sentence saying why not (for a log, not a screen).
     * An offer for another package, an unreadable version code or a code that is not above the installed one is
     * refused first, then each field the install checks need.
     */
    static String refusal(String ownPackage, long installedCode, UpdateOffer o, int deviceSdk) {
        if (o == null) return "no offer";
        if (ownPackage == null || ownPackage.isEmpty()) return "the app's own package name is unknown";
        if (!ownPackage.equals(o.packageName)) return "the offer is for another package";
        if (o.versionCode < 1L) return "the offer has no readable version code";
        if (o.versionCode <= installedCode) return "the offered version is not newer than the installed one";
        if (o.versionName == null || o.versionName.trim().isEmpty()) return "the offer has no version name";
        if (!isHttps(o.downloadUrl)) return "the offer has no https download address";
        if (!isSha256Hex(o.sha256)) return "the offer has no 64-digit sha256";
        if (o.sizeBytes <= 0L) return "the offer has no byte size";
        if (normalizeHex(o.signingFingerprint).isEmpty()) return "the offer has no signing fingerprint";
        if (o.minSdk > 0 && o.minSdk > deviceSdk) return "the offer needs Android API " + o.minSdk + " and this device has " + deviceSdk;
        return null;
    }

    /** True when the two lists (any case or separator) share at least one fingerprint; empty or null never matches. */
    static boolean sharesFingerprint(List<String> a, List<String> b) {
        if (a == null || b == null) return false;
        for (String x : a) {
            String nx = normalizeHex(x);
            if (nx.isEmpty()) continue;
            for (String y : b) {
                if (nx.equals(normalizeHex(y))) return true;
            }
        }
        return false;
    }

    /** SHA-256 of a certificate's bytes as bare lower-case hex. */
    static String certSha256(byte[] certBytes) throws java.security.NoSuchAlgorithmException {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(certBytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    /** Is a start-up check due? A clock that went backwards counts as due (never wait on a future timestamp). */
    static boolean checkDue(long lastCheckMillis, long nowMillis) {
        return lastCheckMillis <= 0L || nowMillis < lastCheckMillis || nowMillis - lastCheckMillis >= MIN_GAP_MILLIS;
    }

    /** Is the installer of record one of the stores that checks updates itself (then this library stays silent)? */
    static boolean isStoreInstaller(String installer, String commaSeparatedStorePackages) {
        if (installer == null || installer.isEmpty() || commaSeparatedStorePackages == null) return false;
        for (String s : commaSeparatedStorePackages.split(",")) {
            if (installer.equals(s.trim())) return true;
        }
        return false;
    }

    /**
     * Task 47i. Should this library say nothing at all for this install? Yes when a store that checks updates itself
     * is the installer of record (47a), and also when ANOTHER package holds the app's update ownership (Android 14,
     * {@code InstallSourceInfo.getUpdateOwnerPackageName()}): that package updates the app, and an install started
     * by anyone else makes Android ask the person to confirm, so a notification from here would only compete with
     * the owner's. An owner equal to the app's own package (it installed itself) is not "another". A null or empty
     * owner (older Android, or none set) changes nothing.
     */
    static boolean shouldStayQuiet(String installer, String updateOwner, String commaSeparatedStorePackages, String ownPackage) {
        if (isStoreInstaller(installer, commaSeparatedStorePackages)) return true;
        if (updateOwner == null || updateOwner.isEmpty()) return false;
        return !updateOwner.equals(ownPackage);
    }

    /** One stable notification id per package, in a range of its own. */
    static int notificationId(String packageName) {
        return 0x5a000000 | (packageName.hashCode() & 0x00ffffff);
    }

    /** A plain sentence for a PackageInstaller status, with Android's own message when it gave one. */
    static String failureReason(int status, String androidMessage) {
        String extra = (androidMessage == null || androidMessage.trim().isEmpty()) ? "" : " (Android said: " + androidMessage.trim() + ")";
        switch (status) {
            case STATUS_FAILURE_ABORTED: return "The update was cancelled." + extra;
            case STATUS_FAILURE_BLOCKED: return "Android blocked the update, for example by a device policy or a security app." + extra;
            case STATUS_FAILURE_CONFLICT:
                return "Android refused the update because it conflicts with the installed app, most often because this copy "
                    + "was signed with a different key (for example a copy from Google Play). Install the update once from "
                    + "the store or website this app came from; after that it can update in place." + extra;
            case STATUS_FAILURE_INCOMPATIBLE: return "The update does not fit this device." + extra;
            case STATUS_FAILURE_INVALID: return "Android found the downloaded file invalid." + extra;
            case STATUS_FAILURE_STORAGE: return "There is not enough storage space to install the update." + extra;
            case STATUS_FAILURE_TIMEOUT: return "The install timed out." + extra;
            default: return "The update could not be installed." + extra;
        }
    }
}
