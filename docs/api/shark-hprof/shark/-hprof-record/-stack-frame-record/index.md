[shark-hprof](../../../index.md) / [shark](../../index.md) / [HprofRecord](../index.md) / [StackFrameRecord](./index.md)

# StackFrameRecord

`class StackFrameRecord : `[`HprofRecord`](../index.md)

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `StackFrameRecord(id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, methodNameStringId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, methodSignatureStringId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, sourceFileNameStringId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, classSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, lineNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`)` |

### Properties

| Name | Summary |
|---|---|
| [classSerialNumber](class-serial-number.md) | `val classSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [id](id.md) | `val id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [lineNumber](line-number.md) | `val lineNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>0 line number 0 no line information available -1 unknown location -2 compiled method (Not implemented) -3 native method (Not implemented) |
| [methodNameStringId](method-name-string-id.md) | `val methodNameStringId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [methodSignatureStringId](method-signature-string-id.md) | `val methodSignatureStringId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [sourceFileNameStringId](source-file-name-string-id.md) | `val sourceFileNameStringId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
