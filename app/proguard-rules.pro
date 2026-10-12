# The root helper is launched by name via app_process, so R8 cannot see its entry point.
-keep class dev.ririxi.bunnylock.root.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
