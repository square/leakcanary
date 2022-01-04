//[leakcanary-android-release](../../../index.md)/[leakcanary](../index.md)/[HeapAnalysisClient](index.md)

# HeapAnalysisClient

[androidJvm]\
class [HeapAnalysisClient](index.md)(heapDumpDirectoryProvider: () -&gt; [File](https://developer.android.com/reference/kotlin/java/io/File.html), config: [HeapAnalysisConfig](../-heap-analysis-config/index.md), interceptors: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[HeapAnalysisInterceptor](../-heap-analysis-interceptor/index.md)&gt;)

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [androidJvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [deleteHeapDumpFiles](delete-heap-dump-files.md) | [androidJvm]<br>fun [deleteHeapDumpFiles](delete-heap-dump-files.md)() |
| [newJob](new-job.md) | [androidJvm]<br>fun [newJob](new-job.md)(context: [JobContext](../-job-context/index.md) = JobContext()): [HeapAnalysisJob](../-heap-analysis-job/index.md) |
