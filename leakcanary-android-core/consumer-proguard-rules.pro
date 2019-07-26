# Loaded via reflection & referenced by shark.AndroidReferenceMatchers.LEAK_CANARY_INTERNAL
-keep class leakcanary.internal.InternalLeakCanary { *; }
# Referenced by shark.AndroidReferenceMatchers.LEAK_CANARY_HEAP_DUMPER
-keep class leakcanary.internal.AndroidHeapDumper { *; }
# Marshmallow removed Notification.setLatestEventInfo()
-dontwarn android.app.Notification
