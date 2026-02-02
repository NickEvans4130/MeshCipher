# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep Signal Protocol classes
-keep class org.signal.** { *; }
-keep class org.whispersystems.** { *; }

# Keep Room entities
-keep class com.meshcipher.data.local.entity.** { *; }
