[shark-hprof](../../../index.md) / [shark](../../index.md) / [HprofRecord](../index.md) / [LoadClassRecord](./index.md)

# LoadClassRecord

`class LoadClassRecord : `[`HprofRecord`](../index.md)

To limit object allocation while parsing, [HprofReader](../../-hprof-reader/index.md) uses a single instance which is
reused after each call to [OnHprofRecordListener.onHprofRecord](../../-on-hprof-record-listener/on-hprof-record.md).

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `LoadClassRecord(classSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, stackTraceSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, classNameStringId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`)`<br>To limit object allocation while parsing, [HprofReader](../../-hprof-reader/index.md) uses a single instance which is reused after each call to [OnHprofRecordListener.onHprofRecord](../../-on-hprof-record-listener/on-hprof-record.md). |

### Properties

| Name | Summary |
|---|---|
| [classNameStringId](class-name-string-id.md) | `var classNameStringId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [classSerialNumber](class-serial-number.md) | `var classSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [id](id.md) | `var id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [stackTraceSerialNumber](stack-trace-serial-number.md) | `var stackTraceSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
