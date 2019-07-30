[shark-hprof](../../../index.md) / [shark](../../index.md) / [GcRoot](../index.md) / [Unreachable](./index.md)

# Unreachable

`class Unreachable : `[`GcRoot`](../index.md)

An object that is unreachable from any other root, but not a root itself.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `Unreachable(id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`)`<br>An object that is unreachable from any other root, but not a root itself. |

### Properties

| Name | Summary |
|---|---|
| [id](id.md) | `val id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The object id of the object that this gc root references. |
