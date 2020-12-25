[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [MinimumMemoryInterceptor](./index.md)

# MinimumMemoryInterceptor

`class MinimumMemoryInterceptor : `[`HeapAnalysisInterceptor`](../-heap-analysis-interceptor/index.md)

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `MinimumMemoryInterceptor(application: Application, minimumRequiredAvailableMemoryBytes: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)` = 100_000_000, processInfo: `[`ProcessInfo`](../-process-info/index.md)` = ProcessInfo.Real)` |

### Functions

| Name | Summary |
|---|---|
| [intercept](intercept.md) | `fun intercept(chain: `[`HeapAnalysisInterceptor.Chain`](../-heap-analysis-interceptor/-chain/index.md)`): `[`HeapAnalysisJob.Result`](../-heap-analysis-job/-result/index.md) |
