[shark-hprof](../../index.md) / [shark](../index.md) / [HprofReader](index.md) / [position](./position.md)

# position

`var position: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)

Starts at [startPosition](start-position.md) and increases as [HprofReader](index.md) reads bytes. This is useful
for tracking the position of content in the backing [source](#). This never resets.

