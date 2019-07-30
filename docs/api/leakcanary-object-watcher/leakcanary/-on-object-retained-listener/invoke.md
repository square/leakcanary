[leakcanary-object-watcher](../../index.md) / [leakcanary](../index.md) / [OnObjectRetainedListener](index.md) / [invoke](./invoke.md)

# invoke

`inline operator fun invoke(crossinline block: () -> `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)`): `[`OnObjectRetainedListener`](index.md)

Utility function to create a [OnObjectRetainedListener](index.md) from the passed in [block](invoke.md#leakcanary.OnObjectRetainedListener.Companion$invoke(kotlin.Function0((kotlin.Unit)))/block) lambda
instead of using the anonymous `object : OnObjectRetainedListener` syntax.

Usage:

``` kotlin
val listener = OnObjectRetainedListener {

}
```

