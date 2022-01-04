//[shark-hprof](../../../index.md)/[shark](../index.md)/[ThrowingCancelableFileSourceProvider](index.md)

# ThrowingCancelableFileSourceProvider

[jvm]\
class [ThrowingCancelableFileSourceProvider](index.md)(file: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html), throwIfCanceled: [Runnable](https://docs.oracle.com/javase/8/docs/api/java/lang/Runnable.html)) : [DualSourceProvider](../-dual-source-provider/index.md)

A [DualSourceProvider](../-dual-source-provider/index.md) that invokes throwIfCanceled before every read, allowing cancellation of IO based work built on top by throwing an exception.

## Constructors

| | |
|---|---|
| [ThrowingCancelableFileSourceProvider](-throwing-cancelable-file-source-provider.md) | [jvm]<br>fun [ThrowingCancelableFileSourceProvider](-throwing-cancelable-file-source-provider.md)(file: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html), throwIfCanceled: [Runnable](https://docs.oracle.com/javase/8/docs/api/java/lang/Runnable.html)) |

## Functions

| Name | Summary |
|---|---|
| [openRandomAccessSource](open-random-access-source.md) | [jvm]<br>open override fun [openRandomAccessSource](open-random-access-source.md)(): [RandomAccessSource](../-random-access-source/index.md) |
| [openStreamingSource](open-streaming-source.md) | [jvm]<br>open override fun [openStreamingSource](open-streaming-source.md)(): [BufferedSource](https://square.github.io/okio/2.x/okio/okio/-buffered-source/index.html) |
