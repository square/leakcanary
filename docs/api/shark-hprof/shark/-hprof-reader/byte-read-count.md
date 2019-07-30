[shark-hprof](../../index.md) / [shark](../index.md) / [HprofReader](index.md) / [byteReadCount](./byte-read-count.md)

# byteReadCount

`var byteReadCount: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)

Starts at [startByteReadCount](start-byte-read-count.md) and increases as [HprofReader](index.md) reads bytes. This is useful
for tracking the position of content in the backing [source](#). This never resets.

