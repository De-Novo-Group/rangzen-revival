# Rangzen ProGuard Rules

# Keep SpongyCastle crypto classes
-keep class org.spongycastle.** { *; }
-dontwarn org.spongycastle.**

# Keep Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class org.denovogroup.rangzen.objects.** { *; }

# Keep Timber
-dontwarn org.jetbrains.annotations.**

# Keep ZXing
-keep class com.journeyapps.** { *; }
-keep class com.google.zxing.** { *; }
