[shark-hprof](../../index.md) / [shark](../index.md) / [HprofReader](./index.md)

# HprofReader

`class ~~HprofReader~~`
**Deprecated:** Replaced by HprofStreamingReader.readerFor or HprofRandomAccessReader.openReaderFor

### Properties

| Name | Summary |
|---|---|
| [identifierByteSize](identifier-byte-size.md) | `val identifierByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [startPosition](start-position.md) | `val startPosition: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |

### Functions

| Name | Summary |
|---|---|
| [readHprofRecords](read-hprof-records.md) | `fun readHprofRecords(recordTypes: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<out `[`HprofRecord`](../-hprof-record/index.md)`>>, listener: `[`OnHprofRecordListener`](../-on-hprof-record-listener/index.md)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
