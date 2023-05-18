# AndroidXFragmentDestroyWatcher is loaded via reflection
-keep class leakcanary.internal.AndroidXFragmentDestroyWatcher { *; }
# ViewModelClearedWatcher reaches into ViewModelStore using reflection.
-keep class androidx.lifecycle.ViewModelStore { *; }
