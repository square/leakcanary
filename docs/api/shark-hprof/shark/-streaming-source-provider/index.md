[shark-hprof](../../index.md) / [shark](../index.md) / [StreamingSourceProvider](./index.md)

# StreamingSourceProvider

`interface StreamingSourceProvider`

Can open [Source](#) instances.

### Functions

| Name | Summary |
|---|---|
| [openStreamingSource](open-streaming-source.md) | `abstract fun openStreamingSource(): BufferedSource` |

### Inheritors

| Name | Summary |
|---|---|
| [DualSourceProvider](../-dual-source-provider.md) | `interface DualSourceProvider : `[`StreamingSourceProvider`](./index.md)`, `[`RandomAccessSourceProvider`](../-random-access-source-provider/index.md)<br>Both a [StreamingSourceProvider](./index.md) and a [RandomAccessSourceProvider](../-random-access-source-provider/index.md) |
