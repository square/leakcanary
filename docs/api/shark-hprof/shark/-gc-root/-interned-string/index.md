[shark-hprof](../../../index.md) / [shark](../../index.md) / [GcRoot](../index.md) / [InternedString](./index.md)

# InternedString

`class InternedString : `[`GcRoot`](../index.md)

An interned string, see [java.lang.String.intern](https://docs.oracle.com/javase/6/docs/api/java/lang/String.html#intern()).

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `InternedString(id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`)`<br>An interned string, see [java.lang.String.intern](https://docs.oracle.com/javase/6/docs/api/java/lang/String.html#intern()). |

### Properties

| Name | Summary |
|---|---|
| [id](id.md) | `val id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The object id of the object that this gc root references. |
