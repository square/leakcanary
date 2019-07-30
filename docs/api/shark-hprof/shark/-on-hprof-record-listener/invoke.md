[shark-hprof](../../index.md) / [shark](../index.md) / [OnHprofRecordListener](index.md) / [invoke](./invoke.md)

# invoke

`inline operator fun invoke(crossinline block: (`[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, `[`HprofRecord`](../-hprof-record/index.md)`) -> `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)`): `[`OnHprofRecordListener`](index.md)

Utility function to create a [OnHprofRecordListener](index.md) from the passed in [block](invoke.md#shark.OnHprofRecordListener.Companion$invoke(kotlin.Function2((kotlin.Long, shark.HprofRecord, kotlin.Unit)))/block) lambda
instead of using the anonymous `object : OnHprofRecordListener` syntax.

Usage:

``` kotlin
val listener = OnHprofRecordListener { position, record ->

}
```

