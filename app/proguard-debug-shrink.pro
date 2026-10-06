# Used only with -PshrinkDebug=true (see app/build.gradle.kts): tree-shake unused
# library code, keep every app class, no renaming, no optimisation.
-dontobfuscate
-dontoptimize
-keep class com.ridetrack.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.annotations.**

# ONNX Runtime (Silero voice detector): classes used from native code.
-keep class ai.onnxruntime.** { *; }
