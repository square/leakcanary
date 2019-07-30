[shark-hprof](../../../index.md) / [shark](../../index.md) / [GcRoot](../index.md) / [ThreadObject](./index.md)

# ThreadObject

`class ThreadObject : `[`GcRoot`](../index.md)

A thread.

Added at https://android.googlesource.com/platform/tools/base/+/c0f0d528c155cab32e372dac77370569a386245c

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `ThreadObject(id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, threadSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, stackTraceSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`)`<br>A thread. |

### Properties

| Name | Summary |
|---|---|
| [id](id.md) | `val id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The object id of the object that this gc root references. |
| [stackTraceSerialNumber](stack-trace-serial-number.md) | `val stackTraceSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [threadSerialNumber](thread-serial-number.md) | `val threadSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
