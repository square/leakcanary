[shark](../../index.md) / [shark](../index.md) / [Leak](index.md) / [groupHash](./group-hash.md)

# groupHash

`val groupHash: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)

A unique SHA1 hash that represents this group of leaks.

For [ApplicationLeak](../-application-leak/index.md) this is based on [LeakTrace.leakCauses](../-leak-trace/leak-causes.md) and for [LibraryLeak](../-library-leak/index.md) this is
based on [LibraryLeak.pattern](../-library-leak/pattern.md).

