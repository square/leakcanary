//[leakcanary-android-core](../../../../../index.md)/[leakcanary](../../../index.md)/[EventListener](../../index.md)/[Event](../index.md)/[HeapAnalysisDone](index.md)

# HeapAnalysisDone

[androidJvm]\
sealed class [HeapAnalysisDone](index.md)&lt;[T](index.md) : HeapAnalysis&gt; : [EventListener.Event](../index.md)

Sent from the thread performing the analysis.

## Types

| Name | Summary |
|---|---|
| [HeapAnalysisFailed](-heap-analysis-failed/index.md) | [androidJvm]<br>class [HeapAnalysisFailed](-heap-analysis-failed/index.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), heapAnalysis: HeapAnalysisFailure, showIntent: [Intent](https://developer.android.com/reference/kotlin/android/content/Intent.html)) : [EventListener.Event.HeapAnalysisDone](index.md)&lt;HeapAnalysisFailure&gt; |
| [HeapAnalysisSucceeded](-heap-analysis-succeeded/index.md) | [androidJvm]<br>class [HeapAnalysisSucceeded](-heap-analysis-succeeded/index.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), heapAnalysis: HeapAnalysisSuccess, unreadLeakSignatures: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)&gt;, showIntent: [Intent](https://developer.android.com/reference/kotlin/android/content/Intent.html)) : [EventListener.Event.HeapAnalysisDone](index.md)&lt;HeapAnalysisSuccess&gt; |

## Properties

| Name | Summary |
|---|---|
| [heapAnalysis](heap-analysis.md) | [androidJvm]<br>val [heapAnalysis](heap-analysis.md): [T](index.md) |
| [showIntent](show-intent.md) | [androidJvm]<br>val [showIntent](show-intent.md): [Intent](https://developer.android.com/reference/kotlin/android/content/Intent.html) |
| [uniqueId](../unique-id.md) | [androidJvm]<br>val [uniqueId](../unique-id.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Unique identifier for a related chain of event. The identifier for the events that run before [HeapDump](../-heap-dump/index.md) gets reset right before [HeapDump](../-heap-dump/index.md) is sent. |

## Inheritors

| Name |
|---|
| [HeapAnalysisSucceeded](-heap-analysis-succeeded/index.md) |
| [HeapAnalysisFailed](-heap-analysis-failed/index.md) |
