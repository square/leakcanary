[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [OncePerPeriodInterceptor](./index.md)

# OncePerPeriodInterceptor

`class OncePerPeriodInterceptor : `[`HeapAnalysisInterceptor`](../-heap-analysis-interceptor/index.md)

Proceeds once per [period](#) (of time) and then cancels all follow up jobs until [period](#) has
passed.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `OncePerPeriodInterceptor(application: Application, periodMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)` = TimeUnit.DAYS.toMillis(1))`<br>Proceeds once per [period](#) (of time) and then cancels all follow up jobs until [period](#) has passed. |

### Functions

| Name | Summary |
|---|---|
| [forget](forget.md) | `fun forget(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [intercept](intercept.md) | `fun intercept(chain: `[`HeapAnalysisInterceptor.Chain`](../-heap-analysis-interceptor/-chain/index.md)`): `[`HeapAnalysisJob.Result`](../-heap-analysis-job/-result/index.md) |
