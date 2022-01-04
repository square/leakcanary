//[leakcanary-android-core](../../../../index.md)/[leakcanary](../../index.md)/[EventListener](../index.md)/[Event](index.md)

# Event

[androidJvm]\
sealed class [Event](index.md) : [Serializable](https://developer.android.com/reference/kotlin/java/io/Serializable.html)

Note: [Event](index.md) is [Serializable](https://developer.android.com/reference/kotlin/java/io/Serializable.html) for convenience but we currently make no guarantee that the Serialization is backward / forward compatible across LeakCanary versions, so plan accordingly. This is convenient for passing events around processes, and shouldn't be used to store them.

## Types

| Name | Summary |
|---|---|
| [DumpingHeap](-dumping-heap/index.md) | [androidJvm]<br>class [DumpingHeap](-dumping-heap/index.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) : [EventListener.Event](index.md)<br>Sent from the "LeakCanary-Heap-Dump" HandlerThread. |
| [HeapAnalysisDone](-heap-analysis-done/index.md) | [androidJvm]<br>sealed class [HeapAnalysisDone](-heap-analysis-done/index.md)&lt;[T](-heap-analysis-done/index.md) : HeapAnalysis&gt; : [EventListener.Event](index.md)<br>Sent from the thread performing the analysis. |
| [HeapAnalysisProgress](-heap-analysis-progress/index.md) | [androidJvm]<br>class [HeapAnalysisProgress](-heap-analysis-progress/index.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), step: OnAnalysisProgressListener.Step, progressPercent: [Double](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-double/index.html)) : [EventListener.Event](index.md)<br>[progressPercent](-heap-analysis-progress/progress-percent.md) is a value between 0..1 |
| [HeapDump](-heap-dump/index.md) | [androidJvm]<br>class [HeapDump](-heap-dump/index.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), file: [File](https://developer.android.com/reference/kotlin/java/io/File.html), durationMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), reason: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) : [EventListener.Event](index.md)<br>Sent from the "LeakCanary-Heap-Dump" HandlerThread. |
| [HeapDumpFailed](-heap-dump-failed/index.md) | [androidJvm]<br>class [HeapDumpFailed](-heap-dump-failed/index.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), exception: [Throwable](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-throwable/index.html), willRetryLater: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)) : [EventListener.Event](index.md)<br>Sent from the "LeakCanary-Heap-Dump" HandlerThread. |

## Properties

| Name | Summary |
|---|---|
| [uniqueId](unique-id.md) | [androidJvm]<br>val [uniqueId](unique-id.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Unique identifier for a related chain of event. The identifier for the events that run before [HeapDump](-heap-dump/index.md) gets reset right before [HeapDump](-heap-dump/index.md) is sent. |

## Inheritors

| Name |
|---|
| [DumpingHeap](-dumping-heap/index.md) |
| [HeapDump](-heap-dump/index.md) |
| [HeapDumpFailed](-heap-dump-failed/index.md) |
| [HeapAnalysisProgress](-heap-analysis-progress/index.md) |
| [HeapAnalysisDone](-heap-analysis-done/index.md) |
