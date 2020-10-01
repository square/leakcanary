[shark-hprof](../../index.md) / [shark](../index.md) / [Hprof](./index.md)

# Hprof

`class ~~Hprof~~ : `[`Closeable`](https://docs.oracle.com/javase/6/docs/api/java/io/Closeable.html)
**Deprecated:** Replaced by HprofStreamingReader.readerFor or HprofRandomAccessReader.openReaderFor

Hprof is deprecated, and we offer partial backward compatibility. Any code that was
previously using HprofReader directly now has to call [StreamingHprofReader.readerFor](../-streaming-hprof-reader/reader-for.md) or
[HprofRandomAcccessReader.readerFor](#)

### Types

| Name | Summary |
|---|---|
| [HprofVersion](-hprof-version/index.md) | `enum class ~~HprofVersion~~` |

### Properties

| Name | Summary |
|---|---|
| [file](file.md) | `val file: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html) |
| [fileLength](file-length.md) | `val fileLength: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [header](header.md) | `val header: `[`HprofHeader`](../-hprof-header/index.md) |
| [heapDumpTimestamp](heap-dump-timestamp.md) | `val heapDumpTimestamp: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [hprofVersion](hprof-version.md) | `val hprofVersion: `[`Hprof.HprofVersion`](-hprof-version/index.md) |
| [reader](reader.md) | `val reader: `[`HprofReader`](../-hprof-reader/index.md) |

### Functions

| Name | Summary |
|---|---|
| [attachClosable](attach-closable.md) | `fun attachClosable(closeable: `[`Closeable`](https://docs.oracle.com/javase/6/docs/api/java/io/Closeable.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Maintains backward compatibility because [Hprof.open](open.md) returns a closeable. This allows consuming libraries to attach a closeable that will be closed whe [Hprof](./index.md) is closed. |
| [close](close.md) | `fun close(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |

### Companion Object Functions

| Name | Summary |
|---|---|
| [open](open.md) | `fun ~~open~~(hprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`): `[`Hprof`](./index.md) |
