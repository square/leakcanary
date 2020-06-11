[shark-hprof](../../../index.md) / [shark](../../index.md) / [HprofRecord](../index.md) / [LoadClassRecord](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`LoadClassRecord(classSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, stackTraceSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, classNameStringId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`)`

To limit object allocation while parsing, [HprofReader](../../-hprof-reader/index.md) uses a single instance which is
reused after each call to [OnHprofRecordListener.onHprofRecord](../../-on-hprof-record-listener/on-hprof-record.md).

