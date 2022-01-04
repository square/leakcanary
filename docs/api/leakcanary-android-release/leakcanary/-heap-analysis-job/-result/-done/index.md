//[leakcanary-android-release](../../../../../index.md)/[leakcanary](../../../index.md)/[HeapAnalysisJob](../../index.md)/[Result](../index.md)/[Done](index.md)

# Done

[androidJvm]\
data class [Done](index.md)(analysis: HeapAnalysis, stripHeapDumpDurationMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)?) : [HeapAnalysisJob.Result](../index.md)

## Properties

| Name | Summary |
|---|---|
| [analysis](analysis.md) | [androidJvm]<br>val [analysis](analysis.md): HeapAnalysis |
| [stripHeapDumpDurationMillis](strip-heap-dump-duration-millis.md) | [androidJvm]<br>val [stripHeapDumpDurationMillis](strip-heap-dump-duration-millis.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)? = null<br>The time spent stripping the hprof of any data if [HeapAnalysisConfig.stripHeapDump](../../../-heap-analysis-config/strip-heap-dump.md) is true, null otherwise. |
