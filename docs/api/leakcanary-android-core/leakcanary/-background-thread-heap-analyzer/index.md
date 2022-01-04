//[leakcanary-android-core](../../../index.md)/[leakcanary](../index.md)/[BackgroundThreadHeapAnalyzer](index.md)

# BackgroundThreadHeapAnalyzer

[androidJvm]\
object [BackgroundThreadHeapAnalyzer](index.md) : [EventListener](../-event-listener/index.md)

Starts heap analysis on a background [HandlerThread](https://developer.android.com/reference/kotlin/android/os/HandlerThread.html) when receiving a [HeapDump](../-event-listener/-event/-heap-dump/index.md) event.

## Functions

| Name | Summary |
|---|---|
| [onEvent](on-event.md) | [androidJvm]<br>open override fun [onEvent](on-event.md)(event: [EventListener.Event](../-event-listener/-event/index.md))<br>[onEvent](on-event.md) is always called from the thread the events are emitted from, which is documented for each event. This enables you to potentially block a chain of events, waiting for some pre work to be done. |
