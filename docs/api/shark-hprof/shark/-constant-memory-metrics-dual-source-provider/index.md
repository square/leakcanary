[shark-hprof](../../index.md) / [shark](../index.md) / [ConstantMemoryMetricsDualSourceProvider](./index.md)

# ConstantMemoryMetricsDualSourceProvider

`class ConstantMemoryMetricsDualSourceProvider : `[`DualSourceProvider`](../-dual-source-provider.md)

Captures IO read metrics without using much memory.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `ConstantMemoryMetricsDualSourceProvider(realSourceProvider: `[`DualSourceProvider`](../-dual-source-provider.md)`)`<br>Captures IO read metrics without using much memory. |

### Properties

| Name | Summary |
|---|---|
| [byteTravelRange](byte-travel-range.md) | `val byteTravelRange: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [randomAccessByteReads](random-access-byte-reads.md) | `var randomAccessByteReads: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [randomAccessByteTravel](random-access-byte-travel.md) | `var randomAccessByteTravel: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [randomAccessReadCount](random-access-read-count.md) | `var randomAccessReadCount: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |

### Functions

| Name | Summary |
|---|---|
| [openRandomAccessSource](open-random-access-source.md) | `fun openRandomAccessSource(): `[`RandomAccessSource`](../-random-access-source/index.md) |
| [openStreamingSource](open-streaming-source.md) | `fun openStreamingSource(): BufferedSource` |
