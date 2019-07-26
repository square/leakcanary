# A ContentProvider that gets created by Android on startup
-keep class leakcanary.internal.AppWatcherInstaller { <init>(); }
# KeyedWeakReference is looked up in the hprof file
-keep class leakcanary.KeyedWeakReference { *; }
