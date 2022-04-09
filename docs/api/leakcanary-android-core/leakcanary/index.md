//[leakcanary-android-core](../../index.md)/[leakcanary](index.md)

# Package leakcanary

## Types

| Name | Summary |
|---|---|
| [AndroidDebugHeapDumper](-android-debug-heap-dumper/index.md) | [androidJvm]<br>object [AndroidDebugHeapDumper](-android-debug-heap-dumper/index.md) : [HeapDumper](-heap-dumper/index.md)<br>Dumps the Android heap using [Debug.dumpHprofData](https://developer.android.com/reference/kotlin/android/os/Debug.html#dumphprofdata). |
| [BackgroundThreadHeapAnalyzer](-background-thread-heap-analyzer/index.md) | [androidJvm]<br>object [BackgroundThreadHeapAnalyzer](-background-thread-heap-analyzer/index.md) : [EventListener](-event-listener/index.md)<br>Starts heap analysis on a background [HandlerThread](https://developer.android.com/reference/kotlin/android/os/HandlerThread.html) when receiving a [HeapDump](-event-listener/-event/-heap-dump/index.md) event. |
| [EventListener](-event-listener/index.md) | [androidJvm]<br>fun interface [EventListener](-event-listener/index.md) |
| [HeapDumper](-heap-dumper/index.md) | [androidJvm]<br>fun interface [HeapDumper](-heap-dumper/index.md) |
| [LazyForwardingEventListener](-lazy-forwarding-event-listener/index.md) | [androidJvm]<br>class [LazyForwardingEventListener](-lazy-forwarding-event-listener/index.md)(lazyEventListener: () -&gt; [EventListener](-event-listener/index.md)) : [EventListener](-event-listener/index.md)<br>Forwards events to the [EventListener](-event-listener/index.md) provided by lazyEventListener which is evaluated lazily, when the first comes in. |
| [LeakCanary](-leak-canary/index.md) | [androidJvm]<br>object [LeakCanary](-leak-canary/index.md)<br>The entry point API for LeakCanary. LeakCanary builds on top of AppWatcher. AppWatcher notifies LeakCanary of retained instances, which in turns dumps the heap, analyses it and publishes the results. |
| [LogcatEventListener](-logcat-event-listener/index.md) | [androidJvm]<br>object [LogcatEventListener](-logcat-event-listener/index.md) : [EventListener](-event-listener/index.md) |
| [NotificationEventListener](-notification-event-listener/index.md) | [androidJvm]<br>object [NotificationEventListener](-notification-event-listener/index.md) : [EventListener](-event-listener/index.md) |
| [RemoteWorkManagerHeapAnalyzer](-remote-work-manager-heap-analyzer/index.md) | [androidJvm]<br>object [RemoteWorkManagerHeapAnalyzer](-remote-work-manager-heap-analyzer/index.md) : [EventListener](-event-listener/index.md)<br>When receiving a [HeapDump](-event-listener/-event/-heap-dump/index.md) event, starts a WorkManager worker that performs heap analysis in a dedicated :leakcanary process |
| [ToastEventListener](-toast-event-listener/index.md) | [androidJvm]<br>object [ToastEventListener](-toast-event-listener/index.md) : [EventListener](-event-listener/index.md) |
| [TvEventListener](-tv-event-listener/index.md) | [androidJvm]<br>object [TvEventListener](-tv-event-listener/index.md) : [EventListener](-event-listener/index.md) |
| [WorkManagerHeapAnalyzer](-work-manager-heap-analyzer/index.md) | [androidJvm]<br>object [WorkManagerHeapAnalyzer](-work-manager-heap-analyzer/index.md) : [EventListener](-event-listener/index.md)<br>When receiving a [HeapDump](-event-listener/-event/-heap-dump/index.md) event, starts a WorkManager worker that performs heap analysis. |
