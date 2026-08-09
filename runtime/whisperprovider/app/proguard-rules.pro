-keep class com.aios.model.** { *; }
-keep class com.aios.runtime.** { *; }
-keep class com.aios.runtime.whispercpp.NativeWhisper { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
