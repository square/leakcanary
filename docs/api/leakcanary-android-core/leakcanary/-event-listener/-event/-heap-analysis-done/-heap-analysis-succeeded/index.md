//[leakcanary-android-core](../../../../../../index.md)/[leakcanary](../../../../index.md)/[EventListener](../../../index.md)/[Event](../../index.md)/[HeapAnalysisDone](../index.md)/[HeapAnalysisSucceeded](index.md)

# HeapAnalysisSucceeded

[androidJvm]\
class [HeapAnalysisSucceeded](index.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), heapAnalysis: HeapAnalysisSuccess, unreadLeakSignatures: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)&gt;, showIntent: [Intent](https://developer.android.com/reference/kotlin/android/content/Intent.html)) : [EventListener.Event.HeapAnalysisDone](../index.md)&lt;HeapAnalysisSuccess&gt;

## Properties

| Name | Summary |
|---|---|
| [heapAnalysis](../heap-analysis.md) | [androidJvm]<br>val [heapAnalysis](../heap-analysis.md): HeapAnalysisSuccess |
| [showIntent](../show-intent.md) | [androidJvm]<br>val [showIntent](../show-intent.md): [Intent](https://developer.android.com/reference/kotlin/android/content/Intent.html) |
| [uniqueId](../../unique-id.md) | [androidJvm]<br>val [uniqueId](../../unique-id.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Unique identifier for a related chain of event. The identifier for the events that run before [HeapDump](../../-heap-dump/index.md) gets reset right before [HeapDump](../../-heap-dump/index.md) is sent. |
| [unreadLeakSignatures](unread-leak-signatures.md) | [androidJvm]<br>val [unreadLeakSignatures](unread-leak-signatures.md): [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)&gt; |
