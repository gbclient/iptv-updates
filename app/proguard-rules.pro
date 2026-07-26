# Keep all app data classes (Gson needs them)
-keep class com.iptv.player.** { *; }
-keepclassmembers class com.iptv.player.** { *; }

# Keep model classes
-keep class com.iptv.player.model.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**
-keep class com.google.gson.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Coil
-keep class coil.** { *; }

# Media3
-keep class androidx.media3.** { *; }

# NanoHTTPd
-keep class fi.iki.elonen.** { *; }

# MQTT Paho
-keep class org.eclipse.paho.** { *; }
