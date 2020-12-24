[leakcanary-android-core](../../index.md) / [leakcanary](../index.md) / [OnHeapAnalyzedListener](./index.md)

# OnHeapAnalyzedListener

`interface OnHeapAnalyzedListener`

### Functions

| Name | Summary |
|---|---|
| [onHeapAnalyzed](on-heap-analyzed.md) | `abstract fun onHeapAnalyzed(heapAnalysis: HeapAnalysis): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |

### Companion Object Functions

| Name | Summary |
|---|---|
| [invoke](invoke.md) | `operator fun invoke(block: (HeapAnalysis) -> `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)`): `[`OnHeapAnalyzedListener`](./index.md)<br>Utility function to create a [OnHeapAnalyzedListener](./index.md) from the passed in [block](invoke.md#leakcanary.OnHeapAnalyzedListener.Companion$invoke(kotlin.Function1((shark.HeapAnalysis, kotlin.Unit)))/block) lambda instead of using the anonymous `object : OnHeapAnalyzedListener` syntax. |

### Inheritors

| Name | Summary |
|---|---|
| [DefaultOnHeapAnalyzedListener](../-default-on-heap-analyzed-listener/index.md) | `class DefaultOnHeapAnalyzedListener : `[`OnHeapAnalyzedListener`](./index.md)<br>Default [OnHeapAnalyzedListener](./index.md) implementation, which will store the analysis to disk and show a notification summarizing the result. |
