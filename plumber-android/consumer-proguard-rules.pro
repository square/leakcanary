# A ContentProvider that gets created by Android on startup
-keep class leakcanary.internal.PlumberInstaller { <init>(); }

# Enum values are referenced reflectively in EnumSet initialization
-keepclassmembers,allowoptimization enum leakcanary.AndroidLeakFixes {
    public static **[] values();
}