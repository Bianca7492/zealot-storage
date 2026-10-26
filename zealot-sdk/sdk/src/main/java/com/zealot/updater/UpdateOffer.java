package com.zealot.updater;

/**
 * Task 47a: what Zealot's update endpoint (`GET /catalog/updates/<package>`, Task 47d) said about the newest
 * installable version. Plain data, no Android imports (the JSON is read in [OfferParser]), so [UpdaterRules] and
 * its self-test run on any JVM. A field the answer lacked is left at its empty value; [UpdaterRules#refusal] is
 * what turns an incomplete offer into "no update", nothing here defaults a missing checksum.
 */
final class UpdateOffer {
    String packageName = "";
    /** 0 when unreadable. */
    long versionCode;
    String versionName = "";
    String downloadUrl = "";
    /** As sent; compared through {@link UpdaterRules#normalizeHex}. */
    String sha256 = "";
    /** 0 when missing. */
    long sizeBytes;
    String signingFingerprint = "";
    /** 0 means the answer named no minimum. */
    int minSdk;
    /** The changelog, trimmed; empty when there is none. */
    String changelog = "";
}
