[shark-hprof](../../index.md) / [shark](../index.md) / [RandomAccessSourceProvider](./index.md)

# RandomAccessSourceProvider

`interface RandomAccessSourceProvider`

Can open [RandomAccessSource](../-random-access-source/index.md) instances.

### Functions

| Name | Summary |
|---|---|
| [openRandomAccessSource](open-random-access-source.md) | `abstract fun openRandomAccessSource(): `[`RandomAccessSource`](../-random-access-source/index.md) |

### Inheritors

| Name | Summary |
|---|---|
| [DualSourceProvider](../-dual-source-provider.md) | `interface DualSourceProvider : `[`StreamingSourceProvider`](../-streaming-source-provider/index.md)`, `[`RandomAccessSourceProvider`](./index.md)<br>Both a [StreamingSourceProvider](../-streaming-source-provider/index.md) and a [RandomAccessSourceProvider](./index.md) |
