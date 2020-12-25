[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [MinimumElapsedSinceStartInterceptor](./index.md)

# MinimumElapsedSinceStartInterceptor

`class MinimumElapsedSinceStartInterceptor : `[`HeapAnalysisInterceptor`](../-heap-analysis-interceptor/index.md)

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `MinimumElapsedSinceStartInterceptor(minimumElapsedSinceStartMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)` = TimeUnit.SECONDS.toMillis(30), processInfo: `[`ProcessInfo`](../-process-info/index.md)` = ProcessInfo.Real)` |

### Functions

| Name | Summary |
|---|---|
| [intercept](intercept.md) | `fun intercept(chain: `[`HeapAnalysisInterceptor.Chain`](../-heap-analysis-interceptor/-chain/index.md)`): `[`HeapAnalysisJob.Result`](../-heap-analysis-job/-result/index.md) |
