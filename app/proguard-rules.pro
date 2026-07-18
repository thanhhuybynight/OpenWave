# OpenWave — keep Media3 / extractors
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# OkHttp / Retrofit
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keepattributes Signature
-keepattributes *Annotation*

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
