[shark](../../index.md) / [shark](../index.md) / [LeakTrace](index.md) / [signature](./signature.md)

# signature

`val signature: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)

A SHA1 hash that represents this leak trace. This can be useful to group together similar
leak traces.

The signature is a hash of [suspectReferenceSubpath](suspect-reference-subpath.md).

