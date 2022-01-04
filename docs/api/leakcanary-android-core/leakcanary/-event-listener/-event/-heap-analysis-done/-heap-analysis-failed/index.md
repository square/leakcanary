//[leakcanary-android-core](../../../../../../index.md)/[leakcanary](../../../../index.md)/[EventListener](../../../index.md)/[Event](../../index.md)/[HeapAnalysisDone](../index.md)/[HeapAnalysisFailed](index.md)

# HeapAnalysisFailed

[androidJvm]\
class [HeapAnalysisFailed](index.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), heapAnalysis: HeapAnalysisFailure, showIntent: [Intent](https://developer.android.com/reference/kotlin/android/content/Intent.html)) : [EventListener.Event.HeapAnalysisDone](../index.md)&lt;HeapAnalysisFailure&gt;

## Properties

| Name | Summary |
|---|---|
| [heapAnalysis](../heap-analysis.md) | [androidJvm]<br>val [heapAnalysis](../heap-analysis.md): HeapAnalysisFailure |
| [showIntent](../show-intent.md) | [androidJvm]<br>val [showIntent](../show-intent.md): [Intent](https://developer.android.com/reference/kotlin/android/content/Intent.html) |
| [uniqueId](../../unique-id.md) | [androidJvm]<br>val [uniqueId](../../unique-id.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Unique identifier for a related chain of event. The identifier for the events that run before [HeapDump](../../-heap-dump/index.md) gets reset right before [HeapDump](../../-heap-dump/index.md) is sent. |
