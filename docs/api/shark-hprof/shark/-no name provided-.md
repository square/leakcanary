[shark-hprof](../index.md) / [shark](index.md) / [&lt;no name provided&gt;](./-no name provided-.md)

# &lt;no name provided&gt;

`fun <no name provided>(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Listener passed in to [StreamingHprofReader.readRecords](-streaming-hprof-reader/read-records.md), gets notified for each [HprofRecord](-hprof-record/index.md)
found in the heap dump which types is in the set of the recordTypes parameter passed to
[StreamingHprofReader.readRecords](-streaming-hprof-reader/read-records.md).

This is a functional interface with which you can create a [OnHprofRecordListener](-on-hprof-record-listener/index.md) from a lambda.

