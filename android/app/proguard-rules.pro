# ProGuard rules for DouyinBilibiliDownloader

# Keep JavascriptInterface methods
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Web-Native bridge model beans
-keep class com.yaoxiovo.downloader.model.** { *; }
-keep class com.yaoxiovo.downloader.bridge.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
