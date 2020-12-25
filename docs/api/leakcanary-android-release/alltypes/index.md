

### All Types

| Name | Summary |
|---|---|
| [leakcanary.BackgroundTrigger](../leakcanary/-background-trigger/index.md) |  |
| [leakcanary.ConditionalInterceptor](../leakcanary/-conditional-interceptor/index.md) | An interceptor that runs only when [evaluateCondition](#) returns true. |
| [leakcanary.GoodAndroidVersionInterceptor](../leakcanary/-good-android-version-interceptor/index.md) |  |
| [leakcanary.HeapAnalysisClient](../leakcanary/-heap-analysis-client/index.md) |  |
| [leakcanary.HeapAnalysisConfig](../leakcanary/-heap-analysis-config/index.md) |  |
| [leakcanary.HeapAnalysisInterceptor](../leakcanary/-heap-analysis-interceptor/index.md) |  |
| [leakcanary.HeapAnalysisJob](../leakcanary/-heap-analysis-job/index.md) | A [HeapAnalysisJob](../leakcanary/-heap-analysis-job/index.md) represents a single prepared request to analyze the heap. It cannot be executed twice. |
| [leakcanary.JobContext](../leakcanary/-job-context/index.md) | In memory store that can be used to store objects in a given [HeapAnalysisJob](../leakcanary/-heap-analysis-job/index.md) instance. This is a simple [MutableMap](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-map/index.html) of [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) to [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), but with unsafe generics access. |
| [leakcanary.MinimumDiskSpaceInterceptor](../leakcanary/-minimum-disk-space-interceptor/index.md) |  |
| [leakcanary.MinimumElapsedSinceStartInterceptor](../leakcanary/-minimum-elapsed-since-start-interceptor/index.md) |  |
| [leakcanary.MinimumMemoryInterceptor](../leakcanary/-minimum-memory-interceptor/index.md) |  |
| [leakcanary.OncePerPeriodInterceptor](../leakcanary/-once-per-period-interceptor/index.md) | Proceeds once per [period](#) (of time) and then cancels all follow up jobs until [period](#) has passed. |
| [leakcanary.ProcessInfo](../leakcanary/-process-info/index.md) |  |
| [leakcanary.SaveResourceIdsInterceptor](../leakcanary/-save-resource-ids-interceptor/index.md) | Interceptor that saves the names of R.id.* entries and their associated int values to a static field that can then be read from the heap dump. |
| [leakcanary.ScreenOffTrigger](../leakcanary/-screen-off-trigger/index.md) |  |
