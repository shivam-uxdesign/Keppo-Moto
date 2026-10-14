# Release (and releaseTest) builds: R8 shrinks, optimises and renames. The libraries (Media3,
# MapLibre, Firebase, Room, Compose, Play services) ship their own rules; these are the app's.
# The app uses no reflection or serialization libraries (saved data is hand-written org.json),
# so its own classes can be renamed safely.

# Readable crash reports: keep line numbers, hide the source file names behind one name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ONNX Runtime (Silero voice detector): classes used from native code.
-keep class ai.onnxruntime.** { *; }

# Optional annotations some libraries refer to but don't ship.
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.annotations.**
