[leakcanary-android-core](../../index.md) / [leakcanary](../index.md) / [OnHeapAnalyzedListener](index.md) / [invoke](./invoke.md)

# invoke

`inline operator fun invoke(crossinline block: (HeapAnalysis) -> `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)`): `[`OnHeapAnalyzedListener`](index.md)

Utility function to create a [OnHeapAnalyzedListener](index.md) from the passed in [block](invoke.md#leakcanary.OnHeapAnalyzedListener.Companion$invoke(kotlin.Function1((shark.HeapAnalysis, kotlin.Unit)))/block) lambda
instead of using the anonymous `object : OnHeapAnalyzedListener` syntax.

Usage:

``` kotlin
val listener = OnHeapAnalyzedListener {

}
```

