[shark](../../index.md) / [shark](../index.md) / [ObjectInspector](index.md) / [invoke](./invoke.md)

# invoke

`inline operator fun invoke(crossinline block: (`[`ObjectReporter`](../-object-reporter/index.md)`) -> `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)`): `[`ObjectInspector`](index.md)

Utility function to create a [ObjectInspector](index.md) from the passed in [block](invoke.md#shark.ObjectInspector.Companion$invoke(kotlin.Function1((shark.ObjectReporter, kotlin.Unit)))/block) lambda instead of
using the anonymous `object : OnHeapAnalyzedListener` syntax.

Usage:

``` kotlin
val inspector = ObjectInspector { reporter ->

}
```

