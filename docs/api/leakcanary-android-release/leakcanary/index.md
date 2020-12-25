[leakcanary-android-release](../index.md) / [leakcanary](./index.md)

## Package leakcanary

### Types

| Name | Summary |
|---|---|
| [BackgroundTrigger](-background-trigger/index.md) | `class BackgroundTrigger` |
| [ConditionalInterceptor](-conditional-interceptor/index.md) | `class ConditionalInterceptor : `[`HeapAnalysisInterceptor`](-heap-analysis-interceptor/index.md)<br>An interceptor that runs only when [evaluateCondition](#) returns true. |
| [GoodAndroidVersionInterceptor](-good-android-version-interceptor/index.md) | `class GoodAndroidVersionInterceptor : `[`HeapAnalysisInterceptor`](-heap-analysis-interceptor/index.md) |
| [HeapAnalysisClient](-heap-analysis-client/index.md) | `class HeapAnalysisClient` |
| [HeapAnalysisConfig](-heap-analysis-config/index.md) | `data class HeapAnalysisConfig` |
| [HeapAnalysisInterceptor](-heap-analysis-interceptor/index.md) | `interface HeapAnalysisInterceptor` |
| [HeapAnalysisJob](-heap-analysis-job/index.md) | `interface HeapAnalysisJob`<br>A [HeapAnalysisJob](-heap-analysis-job/index.md) represents a single prepared request to analyze the heap. It cannot be executed twice. |
| [JobContext](-job-context/index.md) | `class JobContext`<br>In memory store that can be used to store objects in a given [HeapAnalysisJob](-heap-analysis-job/index.md) instance. This is a simple [MutableMap](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-map/index.html) of [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) to [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), but with unsafe generics access. |
| [MinimumDiskSpaceInterceptor](-minimum-disk-space-interceptor/index.md) | `class MinimumDiskSpaceInterceptor : `[`HeapAnalysisInterceptor`](-heap-analysis-interceptor/index.md) |
| [MinimumElapsedSinceStartInterceptor](-minimum-elapsed-since-start-interceptor/index.md) | `class MinimumElapsedSinceStartInterceptor : `[`HeapAnalysisInterceptor`](-heap-analysis-interceptor/index.md) |
| [MinimumMemoryInterceptor](-minimum-memory-interceptor/index.md) | `class MinimumMemoryInterceptor : `[`HeapAnalysisInterceptor`](-heap-analysis-interceptor/index.md) |
| [OncePerPeriodInterceptor](-once-per-period-interceptor/index.md) | `class OncePerPeriodInterceptor : `[`HeapAnalysisInterceptor`](-heap-analysis-interceptor/index.md)<br>Proceeds once per [period](#) (of time) and then cancels all follow up jobs until [period](#) has passed. |
| [ProcessInfo](-process-info/index.md) | `interface ProcessInfo` |
| [SaveResourceIdsInterceptor](-save-resource-ids-interceptor/index.md) | `class SaveResourceIdsInterceptor : `[`HeapAnalysisInterceptor`](-heap-analysis-interceptor/index.md)<br>Interceptor that saves the names of R.id.* entries and their associated int values to a static field that can then be read from the heap dump. |
| [ScreenOffTrigger](-screen-off-trigger/index.md) | `class ScreenOffTrigger` |

### Functions

| Name | Summary |
|---|---|
| [&lt;no name provided&gt;](-no name provided-.md) | `fun <no name provided>(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
