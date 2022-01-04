//[leakcanary-android-core](../../../index.md)/[leakcanary](../index.md)/[EventListener](index.md)

# EventListener

[androidJvm]\
fun interface [EventListener](index.md)

## Types

| Name | Summary |
|---|---|
| [Event](-event/index.md) | [androidJvm]<br>sealed class [Event](-event/index.md) : [Serializable](https://developer.android.com/reference/kotlin/java/io/Serializable.html)<br>Note: [Event](-event/index.md) is [Serializable](https://developer.android.com/reference/kotlin/java/io/Serializable.html) for convenience but we currently make no guarantee that the Serialization is backward / forward compatible across LeakCanary versions, so plan accordingly. This is convenient for passing events around processes, and shouldn't be used to store them. |

## Functions

| Name | Summary |
|---|---|
| [onEvent](on-event.md) | [androidJvm]<br>abstract fun [onEvent](on-event.md)(event: [EventListener.Event](-event/index.md))<br>[onEvent](on-event.md) is always called from the thread the events are emitted from, which is documented for each event. This enables you to potentially block a chain of events, waiting for some pre work to be done. |

## Inheritors

| Name |
|---|
| [BackgroundThreadHeapAnalyzer](../-background-thread-heap-analyzer/index.md) |
| [LogcatEventListener](../-logcat-event-listener/index.md) |
| [NotificationEventListener](../-notification-event-listener/index.md) |
| [RemoteWorkManagerHeapAnalyzer](../-remote-work-manager-heap-analyzer/index.md) |
| [ToastEventListener](../-toast-event-listener/index.md) |
| [TvEventListener](../-tv-event-listener/index.md) |
| [WorkManagerHeapAnalyzer](../-work-manager-heap-analyzer/index.md) |
