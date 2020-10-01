[shark-hprof](../../index.md) / [shark](../index.md) / [OnHprofRecordTagListener](./index.md)

# OnHprofRecordTagListener

`interface OnHprofRecordTagListener`

Listener passed in to [StreamingHprofReader.readRecords](../-streaming-hprof-reader/read-records.md), gets notified for each
[HprofRecordTag](../-hprof-record-tag/index.md) found in the heap dump.

Listener implementations are expected to read all bytes corresponding to a given tag from the
provided reader before returning.

### Functions

| Name | Summary |
|---|---|
| [onHprofRecord](on-hprof-record.md) | `abstract fun onHprofRecord(tag: `[`HprofRecordTag`](../-hprof-record-tag/index.md)`, length: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, reader: `[`HprofRecordReader`](../-hprof-record-reader/index.md)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |

### Companion Object Functions

| Name | Summary |
|---|---|
| [invoke](invoke.md) | `operator fun invoke(block: (`[`HprofRecordTag`](../-hprof-record-tag/index.md)`, `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, `[`HprofRecordReader`](../-hprof-record-reader/index.md)`) -> `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)`): `[`OnHprofRecordTagListener`](./index.md)<br>Utility function to create a [OnHprofRecordTagListener](./index.md) from the passed in [block](invoke.md#shark.OnHprofRecordTagListener.Companion$invoke(kotlin.Function3((shark.HprofRecordTag, kotlin.Long, shark.HprofRecordReader, kotlin.Unit)))/block) lambda instead of using the anonymous `object : OnHprofRecordTagListener` syntax. |
