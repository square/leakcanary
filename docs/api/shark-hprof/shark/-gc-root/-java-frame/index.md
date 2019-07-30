[shark-hprof](../../../index.md) / [shark](../../index.md) / [GcRoot](../index.md) / [JavaFrame](./index.md)

# JavaFrame

`class JavaFrame : `[`GcRoot`](../index.md)

A java local variable

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `JavaFrame(id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, threadSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, frameNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`)`<br>A java local variable |

### Properties

| Name | Summary |
|---|---|
| [frameNumber](frame-number.md) | `val frameNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>frame number in stack trace (-1 for empty) |
| [id](id.md) | `val id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The object id of the object that this gc root references. |
| [threadSerialNumber](thread-serial-number.md) | `val threadSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>Corresponds to [ThreadObject.threadSerialNumber](../-thread-object/thread-serial-number.md) |
