[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [HeapAnalysisClient](./index.md)

# HeapAnalysisClient

`class HeapAnalysisClient`

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `HeapAnalysisClient(heapDumpDirectoryProvider: () -> `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, config: `[`HeapAnalysisConfig`](../-heap-analysis-config/index.md)`, interceptors: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`HeapAnalysisInterceptor`](../-heap-analysis-interceptor/index.md)`>)` |

### Functions

| Name | Summary |
|---|---|
| [deleteHeapDumpFiles](delete-heap-dump-files.md) | `fun deleteHeapDumpFiles(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [newJob](new-job.md) | `fun newJob(context: `[`JobContext`](../-job-context/index.md)` = JobContext()): `[`HeapAnalysisJob`](../-heap-analysis-job/index.md) |

### Companion Object Functions

| Name | Summary |
|---|---|
| [defaultInterceptors](default-interceptors.md) | `fun defaultInterceptors(application: Application): `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`HeapAnalysisInterceptor`](../-heap-analysis-interceptor/index.md)`>` |
