//[leakcanary-android-core](../../../index.md)/[leakcanary](../index.md)/[WorkManagerHeapAnalyzer](index.md)

# WorkManagerHeapAnalyzer

[androidJvm]\
object [WorkManagerHeapAnalyzer](index.md) : [EventListener](../-event-listener/index.md)

When receiving a [HeapDump](../-event-listener/-event/-heap-dump/index.md) event, starts a WorkManager worker that performs heap analysis.

## Functions

| Name | Summary |
|---|---|
| [onEvent](on-event.md) | [androidJvm]<br>open override fun [onEvent](on-event.md)(event: [EventListener.Event](../-event-listener/-event/index.md))<br>[onEvent](on-event.md) is always called from the thread the events are emitted from, which is documented for each event. This enables you to potentially block a chain of events, waiting for some pre work to be done. |
