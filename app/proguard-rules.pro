# NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Camera Controller
-keep class com.cameraserver.usb.camera.** { *; }

# Keep JSON serialization
-keepclassmembers class * {
    @org.json.* <fields>;
}
