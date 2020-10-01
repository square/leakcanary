[shark-hprof](../../index.md) / [shark](../index.md) / [OnHprofRecordTagListener](index.md) / [invoke](./invoke.md)

# invoke

`inline operator fun invoke(crossinline block: (`[`HprofRecordTag`](../-hprof-record-tag/index.md)`, `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, `[`HprofRecordReader`](../-hprof-record-reader/index.md)`) -> `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)`): `[`OnHprofRecordTagListener`](index.md)

Utility function to create a [OnHprofRecordTagListener](index.md) from the passed in [block](invoke.md#shark.OnHprofRecordTagListener.Companion$invoke(kotlin.Function3((shark.HprofRecordTag, kotlin.Long, shark.HprofRecordReader, kotlin.Unit)))/block) lambda
instead of using the anonymous `object : OnHprofRecordTagListener` syntax.

Usage:

``` kotlin
val listener = OnHprofRecordTagListener { tag, length, reader ->

}
```

