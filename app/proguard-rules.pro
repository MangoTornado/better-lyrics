# Kuromoji loads its dictionary from resources inside the jar via reflection-free
# resource lookups, but the class names are referenced from a generated index.
-keep class com.atilika.kuromoji.** { *; }
-keepclassmembers class com.atilika.kuromoji.** { *; }

# kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.betterlyrics.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.betterlyrics.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio platform shims.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# The WebView bridge that receives the Spotify token.
#
# Nothing in Kotlin calls `onToken` — the injected JavaScript does, by name, through
# `addJavascriptInterface`. R8 sees an uncalled method on an otherwise unreferenced class and is
# right to remove it, which in a release build means the harvest waits out its timeout and reports
# a failure that never happens in debug.
-keepclassmembers class com.betterlyrics.app.lyrics.provider.SpotifyBrowserToken$TokenBridge {
    @android.webkit.JavascriptInterface <methods>;
}
