# Enum values are referenced reflectively in EnumSet initialization
-keepclassmembers,allowoptimization enum leakcanary.AndroidLeakFixes {
    public static **[] values();
}
