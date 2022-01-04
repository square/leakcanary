//[shark-hprof](../../../index.md)/[shark](../index.md)/[ConstantMemoryMetricsDualSourceProvider](index.md)

# ConstantMemoryMetricsDualSourceProvider

[jvm]\
class [ConstantMemoryMetricsDualSourceProvider](index.md)(realSourceProvider: [DualSourceProvider](../-dual-source-provider/index.md)) : [DualSourceProvider](../-dual-source-provider/index.md)

Captures IO read metrics without using much memory.

## Constructors

| | |
|---|---|
| [ConstantMemoryMetricsDualSourceProvider](-constant-memory-metrics-dual-source-provider.md) | [jvm]<br>fun [ConstantMemoryMetricsDualSourceProvider](-constant-memory-metrics-dual-source-provider.md)(realSourceProvider: [DualSourceProvider](../-dual-source-provider/index.md)) |

## Functions

| Name | Summary |
|---|---|
| [openRandomAccessSource](open-random-access-source.md) | [jvm]<br>open override fun [openRandomAccessSource](open-random-access-source.md)(): [RandomAccessSource](../-random-access-source/index.md) |
| [openStreamingSource](open-streaming-source.md) | [jvm]<br>open override fun [openStreamingSource](open-streaming-source.md)(): [BufferedSource](https://square.github.io/okio/2.x/okio/okio/-buffered-source/index.html) |

## Properties

| Name | Summary |
|---|---|
| [byteTravelRange](byte-travel-range.md) | [jvm]<br>val [byteTravelRange](byte-travel-range.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [randomAccessByteReads](random-access-byte-reads.md) | [jvm]<br>var [randomAccessByteReads](random-access-byte-reads.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) = 0 |
| [randomAccessByteTravel](random-access-byte-travel.md) | [jvm]<br>var [randomAccessByteTravel](random-access-byte-travel.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) = 0 |
| [randomAccessReadCount](random-access-read-count.md) | [jvm]<br>var [randomAccessReadCount](random-access-read-count.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) = 0 |
