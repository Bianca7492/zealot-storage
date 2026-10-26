# R8 rules for building the SDK's own release artifact. The consumer rules (consumer-rules.pro)
# are the ones that matter to a host app; these keep the same classes during the SDK's own
# minification pass, and must not drift from them.
-keep class sx.proxies.peer.** { *; }
-keep class com.zealot.proxy.** { *; }
-keep class com.zealot.updater.** { *; }
-keep class com.zealot.sdk.** { *; }
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-keep class com.google.gson.** { *; }
-keep class fi.iki.elonen.** { *; }
-keep class androidx.security.crypto.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn fi.iki.elonen.**
