//[shark-hprof](../../../index.md)/[shark](../index.md)/[DualSourceProvider](index.md)

# DualSourceProvider

[jvm]\
interface [DualSourceProvider](index.md) : [StreamingSourceProvider](../-streaming-source-provider/index.md), [RandomAccessSourceProvider](../-random-access-source-provider/index.md)

Both a [StreamingSourceProvider](../-streaming-source-provider/index.md) and a [RandomAccessSourceProvider](../-random-access-source-provider/index.md)

## Functions

| Name | Summary |
|---|---|
| [openRandomAccessSource](../-random-access-source-provider/open-random-access-source.md) | [jvm]<br>abstract fun [openRandomAccessSource](../-random-access-source-provider/open-random-access-source.md)(): [RandomAccessSource](../-random-access-source/index.md) |
| [openStreamingSource](../-streaming-source-provider/open-streaming-source.md) | [jvm]<br>abstract fun [openStreamingSource](../-streaming-source-provider/open-streaming-source.md)(): [BufferedSource](https://square.github.io/okio/2.x/okio/okio/-buffered-source/index.html) |

## Inheritors

| Name |
|---|
| [ByteArraySourceProvider](../-byte-array-source-provider/index.md) |
| [ConstantMemoryMetricsDualSourceProvider](../-constant-memory-metrics-dual-source-provider/index.md) |
| [FileSourceProvider](../-file-source-provider/index.md) |
| [ThrowingCancelableFileSourceProvider](../-throwing-cancelable-file-source-provider/index.md) |
