# Rules for the release build. They are only read when `isMinifyEnabled` is on, which
# it is not today — see the comment in app/build.gradle.kts. They are written now
# because turning shrinking on without them produces an APK that builds, installs and
# crashes: R8 renames the classes the JNI layer looks up by name and the app dies with
# `UnsatisfiedLinkError` the first time it tries to speak.

# The bundled sherpa-onnx AAR ships a zero-byte proguard.txt, so nothing is keeping its
# native-facing classes. `com.k2fsa.sherpa.onnx.**` is reached from Kotlin *and* from
# JNI (`FindClass` on the native side), and JNI lookup is by string, so neither the
# entry points nor the fields/methods the native library reads may be renamed.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# The general rule behind the one above: a native method's declaring class and name are
# part of the ABI. Without this, any future `external fun` is renamed and the library
# can no longer find it.
-keepclasseswithmembernames class * { native <methods>; }

# Keep default Android behaviour; the app has no reflection-heavy dependencies.
-dontwarn org.jetbrains.annotations.**