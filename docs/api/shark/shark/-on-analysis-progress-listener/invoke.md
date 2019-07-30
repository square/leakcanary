[shark](../../index.md) / [shark](../index.md) / [OnAnalysisProgressListener](index.md) / [invoke](./invoke.md)

# invoke

`inline operator fun invoke(crossinline block: (`[`OnAnalysisProgressListener.Step`](-step/index.md)`) -> `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)`): `[`OnAnalysisProgressListener`](index.md)

Utility function to create a [OnAnalysisProgressListener](index.md) from the passed in [block](invoke.md#shark.OnAnalysisProgressListener.Companion$invoke(kotlin.Function1((shark.OnAnalysisProgressListener.Step, kotlin.Unit)))/block) lambda
instead of using the anonymous `object : OnAnalysisProgressListener` syntax.

Usage:

``` kotlin
val listener = OnAnalysisProgressListener {

}
```

