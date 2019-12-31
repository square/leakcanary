[shark](../../index.md) / [shark](../index.md) / [ApplicationLeak](index.md) / [signature](./signature.md)

# signature

`val signature: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)

Overrides [Leak.signature](../-leak/signature.md)

A unique SHA1 hash that represents this group of leak traces.

For [ApplicationLeak](index.md) this is based on [LeakTrace.signature](../-leak-trace/signature.md) and for [LibraryLeak](../-library-leak/index.md) this is
based on [LibraryLeak.pattern](../-library-leak/pattern.md).

