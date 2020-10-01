[shark-hprof](../index.md) / [shark](index.md) / [DualSourceProvider](./-dual-source-provider.md)

# DualSourceProvider

`interface DualSourceProvider : `[`StreamingSourceProvider`](-streaming-source-provider/index.md)`, `[`RandomAccessSourceProvider`](-random-access-source-provider/index.md)

Both a [StreamingSourceProvider](-streaming-source-provider/index.md) and a [RandomAccessSourceProvider](-random-access-source-provider/index.md)

### Inherited Functions

| Name | Summary |
|---|---|
| [openRandomAccessSource](-random-access-source-provider/open-random-access-source.md) | `abstract fun openRandomAccessSource(): `[`RandomAccessSource`](-random-access-source/index.md) |
| [openStreamingSource](-streaming-source-provider/open-streaming-source.md) | `abstract fun openStreamingSource(): BufferedSource` |

### Inheritors

| Name | Summary |
|---|---|
| [ByteArraySourceProvider](-byte-array-source-provider/index.md) | `class ByteArraySourceProvider : `[`DualSourceProvider`](./-dual-source-provider.md) |
| [ConstantMemoryMetricsDualSourceProvider](-constant-memory-metrics-dual-source-provider/index.md) | `class ConstantMemoryMetricsDualSourceProvider : `[`DualSourceProvider`](./-dual-source-provider.md)<br>Captures IO read metrics without using much memory. |
| [FileSourceProvider](-file-source-provider/index.md) | `class FileSourceProvider : `[`DualSourceProvider`](./-dual-source-provider.md) |
