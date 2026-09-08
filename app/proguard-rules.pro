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
