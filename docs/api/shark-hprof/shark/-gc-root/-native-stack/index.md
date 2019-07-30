[shark-hprof](../../../index.md) / [shark](../../index.md) / [GcRoot](../index.md) / [NativeStack](./index.md)

# NativeStack

`class NativeStack : `[`GcRoot`](../index.md)

Input or output parameters in native code

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `NativeStack(id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, threadSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`)`<br>Input or output parameters in native code |

### Properties

| Name | Summary |
|---|---|
| [id](id.md) | `val id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The object id of the object that this gc root references. |
| [threadSerialNumber](thread-serial-number.md) | `val threadSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>Corresponds to [ThreadObject.threadSerialNumber](../-thread-object/thread-serial-number.md) Note: the corresponding thread is sometimes not found, see: https://issuetracker.google.com/issues/122713143 |
