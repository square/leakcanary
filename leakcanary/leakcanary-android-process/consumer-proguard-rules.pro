# A ContentProvider that gets created by Android on :leakcanary process startup
-keep class leakcanary.internal.LeakCanaryProcessAppWatcherInstaller { <init>(); }
