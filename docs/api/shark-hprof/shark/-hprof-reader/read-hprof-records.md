[shark-hprof](../../index.md) / [shark](../index.md) / [HprofReader](index.md) / [readHprofRecords](./read-hprof-records.md)

# readHprofRecords

`fun readHprofRecords(recordTypes: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<out `[`HprofRecord`](../-hprof-record/index.md)`>>, listener: `[`OnHprofRecordListener`](../-on-hprof-record-listener/index.md)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Reads all hprof records from [source](#).
Assumes the [reader](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io/java.io.-file/reader.html) was has a source that currently points to the start position of hprof
records.

