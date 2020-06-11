[leakcanary-android-core](../../index.md) / [leakcanary](../index.md) / [DefaultOnHeapAnalyzedListener](./index.md)

# DefaultOnHeapAnalyzedListener

`class DefaultOnHeapAnalyzedListener : `[`OnHeapAnalyzedListener`](../-on-heap-analyzed-listener/index.md)

Default [OnHeapAnalyzedListener](../-on-heap-analyzed-listener/index.md) implementation, which will store the analysis to disk and
show a notification summarizing the result.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `DefaultOnHeapAnalyzedListener(application: Application)` |

### Functions

| Name | Summary |
|---|---|
| [onHeapAnalyzed](on-heap-analyzed.md) | `fun onHeapAnalyzed(heapAnalysis: HeapAnalysis): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |

### Companion Object Functions

| Name | Summary |
|---|---|
| [create](create.md) | `fun create(): `[`OnHeapAnalyzedListener`](../-on-heap-analyzed-listener/index.md) |
