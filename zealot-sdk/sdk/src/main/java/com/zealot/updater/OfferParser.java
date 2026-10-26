package com.zealot.updater;

import org.json.JSONObject;

/**
 * Task 47a: reads the JSON the update endpoint answers with (docs/catalog_updates_v1.md). Kept apart from
 * {@link UpdateOffer} because org.json comes with Android, so the data class and the rules stay JVM-testable.
 * A malformed body gives null; a body that parses but lacks fields gives an offer {@link UpdaterRules#refusal}
 * will refuse. `version_code`, `size_bytes` and `min_sdk` may arrive as text or as numbers.
 */
final class OfferParser {
    private OfferParser() {}

    static UpdateOffer parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            UpdateOffer r = new UpdateOffer();
            r.packageName = text(o, "package_name");
            r.versionCode = number(o, "version_code");
            r.versionName = text(o, "version_name");
            r.downloadUrl = text(o, "download_url");
            r.sha256 = text(o, "sha256");
            r.sizeBytes = number(o, "size_bytes");
            r.signingFingerprint = text(o, "signing_fingerprint");
            r.minSdk = (int) Math.max(0L, Math.min(number(o, "min_sdk"), Integer.MAX_VALUE));
            r.changelog = text(o, "changelog");
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return "";
        return String.valueOf(o.opt(key)).trim();
    }

    private static long number(JSONObject o, String key) {
        try {
            String s = text(o, key);
            return s.isEmpty() ? 0L : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
