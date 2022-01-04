//[leakcanary-android-core](../../../../../index.md)/[leakcanary](../../../index.md)/[EventListener](../../index.md)/[Event](../index.md)/[HeapAnalysisProgress](index.md)

# HeapAnalysisProgress

[androidJvm]\
class [HeapAnalysisProgress](index.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), step: OnAnalysisProgressListener.Step, progressPercent: [Double](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-double/index.html)) : [EventListener.Event](../index.md)

[progressPercent](progress-percent.md) is a value between 0..1

Sent from the thread performing the analysis.

## Constructors

| | |
|---|---|
| [HeapAnalysisProgress](-heap-analysis-progress.md) | [androidJvm]<br>fun [HeapAnalysisProgress](-heap-analysis-progress.md)(uniqueId: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), step: OnAnalysisProgressListener.Step, progressPercent: [Double](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-double/index.html)) |

## Properties

| Name | Summary |
|---|---|
| [progressPercent](progress-percent.md) | [androidJvm]<br>val [progressPercent](progress-percent.md): [Double](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-double/index.html) |
| [step](step.md) | [androidJvm]<br>val [step](step.md): OnAnalysisProgressListener.Step |
| [uniqueId](../unique-id.md) | [androidJvm]<br>val [uniqueId](../unique-id.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Unique identifier for a related chain of event. The identifier for the events that run before [HeapDump](../-heap-dump/index.md) gets reset right before [HeapDump](../-heap-dump/index.md) is sent. |
