[shark-hprof](../../index.md) / [shark](../index.md) / [Hprof](index.md) / [attachClosable](./attach-closable.md)

# attachClosable

`fun attachClosable(closeable: `[`Closeable`](https://docs.oracle.com/javase/6/docs/api/java/io/Closeable.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Maintains backward compatibility because [Hprof.open](open.md) returns a closeable. This allows
consuming libraries to attach a closeable that will be closed whe [Hprof](index.md) is closed.

