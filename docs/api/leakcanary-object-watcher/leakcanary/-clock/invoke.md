[leakcanary-object-watcher](../../index.md) / [leakcanary](../index.md) / [Clock](index.md) / [invoke](./invoke.md)

# invoke

`inline operator fun invoke(crossinline block: () -> `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`Clock`](index.md)

Utility function to create a [Clock](index.md) from the passed in [block](invoke.md#leakcanary.Clock.Companion$invoke(kotlin.Function0((kotlin.Long)))/block) lambda
instead of using the anonymous `object : Clock` syntax.

Usage:

``` kotlin
val clock = Clock {

}
```

