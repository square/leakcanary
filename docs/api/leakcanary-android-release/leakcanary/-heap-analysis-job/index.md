[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [HeapAnalysisJob](./index.md)

# HeapAnalysisJob

`interface HeapAnalysisJob`

A [HeapAnalysisJob](./index.md) represents a single prepared request to analyze the heap. It cannot be
executed twice.

### Types

| Name | Summary |
|---|---|
| [Result](-result/index.md) | `sealed class Result` |

### Properties

| Name | Summary |
|---|---|
| [canceled](canceled.md) | `abstract val canceled: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>true of [cancel](cancel.md) has been called or if an [HeapAnalysisInterceptor](../-heap-analysis-interceptor/index.md) has returned [Result.Canceled](-result/-canceled/index.md) from [HeapAnalysisInterceptor.intercept](../-heap-analysis-interceptor/intercept.md). |
| [context](context.md) | `abstract val context: `[`JobContext`](../-job-context/index.md)<br>In memory store, mutable and thread safe. This allows passing data to interceptors. |
| [executed](executed.md) | `abstract val executed: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>true if [execute](execute.md) has been called. It is an error to call [execute](execute.md) more than once. |

### Functions

| Name | Summary |
|---|---|
| [cancel](cancel.md) | `abstract fun cancel(cancelReason: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Cancels the job, if possible. Jobs that are already complete cannot be canceled. |
| [execute](execute.md) | `abstract fun execute(): `[`HeapAnalysisJob.Result`](-result/index.md)<br>Starts the analysis job immediately, and blocks until a result is available. |
