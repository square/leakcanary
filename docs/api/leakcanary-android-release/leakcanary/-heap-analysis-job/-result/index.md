//[leakcanary-android-release](../../../../index.md)/[leakcanary](../../index.md)/[HeapAnalysisJob](../index.md)/[Result](index.md)

# Result

[androidJvm]\
sealed class [Result](index.md)

## Types

| Name | Summary |
|---|---|
| [Canceled](-canceled/index.md) | [androidJvm]<br>data class [Canceled](-canceled/index.md)(cancelReason: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) : [HeapAnalysisJob.Result](index.md) |
| [Done](-done/index.md) | [androidJvm]<br>data class [Done](-done/index.md)(analysis: HeapAnalysis, stripHeapDumpDurationMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)?) : [HeapAnalysisJob.Result](index.md) |

## Inheritors

| Name |
|---|
| [Done](-done/index.md) |
| [Canceled](-canceled/index.md) |
