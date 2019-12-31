[shark](../../index.md) / [shark](../index.md) / [LeakingObjectFinder](index.md) / [invoke](./invoke.md)

# invoke

`inline operator fun invoke(crossinline block: (HeapGraph) -> `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`>): `[`LeakingObjectFinder`](index.md)

Utility function to create a [LeakingObjectFinder](index.md) from the passed in [block](invoke.md#shark.LeakingObjectFinder.Companion$invoke(kotlin.Function1((shark.HeapGraph, kotlin.collections.Set((kotlin.Long)))))/block) lambda
instead of using the anonymous `object : LeakingObjectFinder` syntax.

Usage:

``` kotlin
val listener = LeakingObjectFinder {

}
```

