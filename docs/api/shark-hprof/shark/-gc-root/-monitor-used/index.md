[shark-hprof](../../../index.md) / [shark](../../index.md) / [GcRoot](../index.md) / [MonitorUsed](./index.md)

# MonitorUsed

`class MonitorUsed : `[`GcRoot`](../index.md)

Everything that called the wait() or notify() methods, or
that is synchronized.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `MonitorUsed(id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`)`<br>Everything that called the wait() or notify() methods, or that is synchronized. |

### Properties

| Name | Summary |
|---|---|
| [id](id.md) | `val id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The object id of the object that this gc root references. |
