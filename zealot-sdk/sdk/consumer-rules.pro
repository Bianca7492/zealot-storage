# Consumer ProGuard/R8 rules — shipped inside the AAR and applied automatically in every host
# app, so a host needs no hand-written keep rules (the old dex-injection flow needed a dozen,
# which is one of the failure modes this SDK removes).
#
# The SDK reaches several of its own classes and host-provided libraries by name at runtime
# (Class.forName for the peer SDK, reflection in the updater, the ContentProviders the system
# instantiates from the merged manifest). R8 renaming or removing any of them breaks startup.

# --- The SDK's own API and the classes the system instantiates from the manifest ---
-keep class sx.proxies.peer.** { *; }
-keep class com.zealot.proxy.** { *; }
-keep class com.zealot.updater.** { *; }
-keep class com.zealot.sdk.** { *; }
-dontwarn sx.proxies.peer.**
-dontwarn com.zealot.**

# --- The libraries the SDK loads by original name from the host at runtime ---
# Kotlin stdlib + coroutines
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
# OkHttp / Okio (incl. WebSocket)
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
# Gson (reflective JSON)
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
# Encrypted SharedPreferences (androidx.security:security-crypto) and the Tink crypto library it
# pulls in. Tink references errorprone's annotations at compile time only; they are not on the
# runtime classpath, so without the `-dontwarn` below a *minified host app* fails R8 with
# "Missing class com.google.errorprone.annotations.*" even though the host never mentions Tink.
# The `-keep` is the other half: R8 must not strip the Tink classes, or the encrypted store fails
# at runtime. Both live here so a host app needs no rules of its own.
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**

# NanoHTTPD (the SDK's local proxy server)
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
# androidx.core's NotificationCompat is used from the SDK's service; a minified host that never
# calls it itself would otherwise strip it (a real crash seen in the field).
-keep class androidx.core.app.NotificationCompat** { *; }
