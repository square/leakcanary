//[shark](../../../index.md)/[shark](../index.md)/[OnAnalysisProgressListener](index.md)

# OnAnalysisProgressListener

[jvm]\
fun interface [OnAnalysisProgressListener](index.md)

Reports progress from the [HeapAnalyzer](../-heap-analyzer/index.md) as they occur, as [Step](-step/index.md) values.

This is a functional interface with which you can create a [OnAnalysisProgressListener](index.md) from a lambda.

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |
| [Step](-step/index.md) | [jvm]<br>enum [Step](-step/index.md) : [Enum](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-enum/index.html)&lt;[OnAnalysisProgressListener.Step](-step/index.md)&gt; |

## Functions

| Name | Summary |
|---|---|
| [onAnalysisProgress](on-analysis-progress.md) | [jvm]<br>abstract fun [onAnalysisProgress](on-analysis-progress.md)(step: [OnAnalysisProgressListener.Step](-step/index.md)) |
