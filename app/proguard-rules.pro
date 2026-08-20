-keepattributes *Annotation*
-keepclassmembers class * {
    @org.jetbrains.annotations.NotNull <fields>;
}
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
