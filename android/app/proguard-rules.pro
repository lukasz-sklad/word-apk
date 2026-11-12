# Proguard rules for WordCraft Android

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class one.lmk.wordcraft.bridge.** { *; }
