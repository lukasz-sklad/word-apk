# Proguard rules for WordCraft Android

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class one.lmk.wordcraft.** { *; }
-keep class com.google.androidgamesdk.** { *; }
