[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [ConditionalInterceptor](./index.md)

# ConditionalInterceptor

`class ConditionalInterceptor : `[`HeapAnalysisInterceptor`](../-heap-analysis-interceptor/index.md)

An interceptor that runs only when [evaluateCondition](#) returns true.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `ConditionalInterceptor(delegate: `[`HeapAnalysisInterceptor`](../-heap-analysis-interceptor/index.md)`, evaluateCondition: (`[`HeapAnalysisJob`](../-heap-analysis-job/index.md)`) -> `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)`)`<br>An interceptor that runs only when [evaluateCondition](#) returns true. |

### Functions

| Name | Summary |
|---|---|
| [intercept](intercept.md) | `fun intercept(chain: `[`HeapAnalysisInterceptor.Chain`](../-heap-analysis-interceptor/-chain/index.md)`): `[`HeapAnalysisJob.Result`](../-heap-analysis-job/-result/index.md) |
