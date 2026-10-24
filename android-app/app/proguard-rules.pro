# ── SmartPetFeeder ProGuard / R8 rules ────────────────────────────────────

# Firebase Realtime Database — keep model classes used for deserialization
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class com.smartpetfeeder.model.** {
    <fields>;
    <init>();
}

# Firebase — generic keep rules
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# AndroidX Security (EncryptedSharedPreferences)
-keep class androidx.security.crypto.** { *; }

# Suppress warnings for removed Kotlin stdlib APIs
-dontwarn kotlin.**
