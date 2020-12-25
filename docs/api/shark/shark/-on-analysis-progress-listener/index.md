[shark](../../index.md) / [shark](../index.md) / [OnAnalysisProgressListener](./index.md)

# OnAnalysisProgressListener

`interface OnAnalysisProgressListener`

### Types

| Name | Summary |
|---|---|
| [Step](-step/index.md) | `enum class Step` |

### Functions

| Name | Summary |
|---|---|
| [onAnalysisProgress](on-analysis-progress.md) | `abstract fun onAnalysisProgress(step: `[`OnAnalysisProgressListener.Step`](-step/index.md)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |

### Companion Object Properties

| Name | Summary |
|---|---|
| [NO_OP](-n-o_-o-p.md) | `val NO_OP: `[`OnAnalysisProgressListener`](./index.md)<br>A no-op [OnAnalysisProgressListener](./index.md) |

### Companion Object Functions

| Name | Summary |
|---|---|
| [invoke](invoke.md) | `operator fun invoke(block: (`[`OnAnalysisProgressListener.Step`](-step/index.md)`) -> `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)`): `[`OnAnalysisProgressListener`](./index.md)<br>Utility function to create a [OnAnalysisProgressListener](./index.md) from the passed in [block](invoke.md#shark.OnAnalysisProgressListener.Companion$invoke(kotlin.Function1((shark.OnAnalysisProgressListener.Step, kotlin.Unit)))/block) lambda instead of using the anonymous `object : OnAnalysisProgressListener` syntax. |
