[shark-hprof](../../index.md) / [shark](../index.md) / [OnHprofRecordListener](./index.md)

# OnHprofRecordListener

`interface OnHprofRecordListener`

Listener passed in to [StreamingHprofReader.readRecords](../-streaming-hprof-reader/read-records.md), gets notified for each [HprofRecord](../-hprof-record/index.md)
found in the heap dump which types is in the set of the recordTypes parameter passed to
[StreamingHprofReader.readRecords](../-streaming-hprof-reader/read-records.md).

### Functions

| Name | Summary |
|---|---|
| [onHprofRecord](on-hprof-record.md) | `abstract fun onHprofRecord(position: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, record: `[`HprofRecord`](../-hprof-record/index.md)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |

### Companion Object Functions

| Name | Summary |
|---|---|
| [invoke](invoke.md) | `operator fun invoke(block: (`[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, `[`HprofRecord`](../-hprof-record/index.md)`) -> `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)`): `[`OnHprofRecordListener`](./index.md)<br>Utility function to create a [OnHprofRecordListener](./index.md) from the passed in [block](invoke.md#shark.OnHprofRecordListener.Companion$invoke(kotlin.Function2((kotlin.Long, shark.HprofRecord, kotlin.Unit)))/block) lambda instead of using the anonymous `object : OnHprofRecordListener` syntax. |
