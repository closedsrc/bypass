# The mihomo AAR ships consumer rules for its JNI bridge; these are the app side of the
# same contract, so a minified release cannot lose the classes native code looks up.
-keep class io.github.oviron.libmihomo.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn io.github.oviron.libmihomo.**
