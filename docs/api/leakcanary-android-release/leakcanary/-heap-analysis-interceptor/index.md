[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [HeapAnalysisInterceptor](./index.md)

# HeapAnalysisInterceptor

`interface HeapAnalysisInterceptor`

### Types

| Name | Summary |
|---|---|
| [Chain](-chain/index.md) | `interface Chain` |

### Functions

| Name | Summary |
|---|---|
| [intercept](intercept.md) | `abstract fun intercept(chain: `[`HeapAnalysisInterceptor.Chain`](-chain/index.md)`): `[`HeapAnalysisJob.Result`](../-heap-analysis-job/-result/index.md) |

### Inheritors

| Name | Summary |
|---|---|
| [ConditionalInterceptor](../-conditional-interceptor/index.md) | `class ConditionalInterceptor : `[`HeapAnalysisInterceptor`](./index.md)<br>An interceptor that runs only when [evaluateCondition](#) returns true. |
| [GoodAndroidVersionInterceptor](../-good-android-version-interceptor/index.md) | `class GoodAndroidVersionInterceptor : `[`HeapAnalysisInterceptor`](./index.md) |
| [MinimumDiskSpaceInterceptor](../-minimum-disk-space-interceptor/index.md) | `class MinimumDiskSpaceInterceptor : `[`HeapAnalysisInterceptor`](./index.md) |
| [MinimumElapsedSinceStartInterceptor](../-minimum-elapsed-since-start-interceptor/index.md) | `class MinimumElapsedSinceStartInterceptor : `[`HeapAnalysisInterceptor`](./index.md) |
| [MinimumMemoryInterceptor](../-minimum-memory-interceptor/index.md) | `class MinimumMemoryInterceptor : `[`HeapAnalysisInterceptor`](./index.md) |
| [OncePerPeriodInterceptor](../-once-per-period-interceptor/index.md) | `class OncePerPeriodInterceptor : `[`HeapAnalysisInterceptor`](./index.md)<br>Proceeds once per [period](#) (of time) and then cancels all follow up jobs until [period](#) has passed. |
| [SaveResourceIdsInterceptor](../-save-resource-ids-interceptor/index.md) | `class SaveResourceIdsInterceptor : `[`HeapAnalysisInterceptor`](./index.md)<br>Interceptor that saves the names of R.id.* entries and their associated int values to a static field that can then be read from the heap dump. |
