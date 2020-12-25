[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [SaveResourceIdsInterceptor](./index.md)

# SaveResourceIdsInterceptor

`class SaveResourceIdsInterceptor : `[`HeapAnalysisInterceptor`](../-heap-analysis-interceptor/index.md)

Interceptor that saves the names of R.id.* entries and their associated int values to a static
field that can then be read from the heap dump.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `SaveResourceIdsInterceptor(resources: Resources)`<br>Interceptor that saves the names of R.id.* entries and their associated int values to a static field that can then be read from the heap dump. |

### Functions

| Name | Summary |
|---|---|
| [intercept](intercept.md) | `fun intercept(chain: `[`HeapAnalysisInterceptor.Chain`](../-heap-analysis-interceptor/-chain/index.md)`): `[`HeapAnalysisJob.Result`](../-heap-analysis-job/-result/index.md) |
