[shark](../../index.md) / [shark](../index.md) / [Leak](index.md) / [retainedHeapByteSize](./retained-heap-byte-size.md)

# retainedHeapByteSize

`abstract val retainedHeapByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`?`

The number of bytes which would be freed if all references to the leaking object were
released. Null if the retained heap size was not computed.

