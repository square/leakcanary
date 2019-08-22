[shark-hprof](../../index.md) / [shark](../index.md) / [Hprof](./index.md)

# Hprof

`class Hprof : `[`Closeable`](https://docs.oracle.com/javase/6/docs/api/java/io/Closeable.html)

An opened Hprof file which can be read via [reader](reader.md). Open a new hprof with [open](open.md), and don't
forget to call [close](close.md) once done.

### Types

| Name | Summary |
|---|---|
| [HprofVersion](-hprof-version/index.md) | `enum class HprofVersion`<br>Supported hprof versions |

### Properties

| Name | Summary |
|---|---|
| [fileLength](file-length.md) | `val fileLength: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Length of the hprof file, in bytes. |
| [heapDumpTimestamp](heap-dump-timestamp.md) | `val heapDumpTimestamp: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Unix timestamp at which the heap was dumped. |
| [hprofVersion](hprof-version.md) | `val hprofVersion: `[`Hprof.HprofVersion`](-hprof-version/index.md)<br>Version of the opened hprof, which is tied to the runtime where the heap was dumped. |
| [reader](reader.md) | `val reader: `[`HprofReader`](../-hprof-reader/index.md) |

### Functions

| Name | Summary |
|---|---|
| [close](close.md) | `fun close(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [moveReaderTo](move-reader-to.md) | `fun moveReaderTo(newPosition: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Moves [reader](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io/java.io.-file/reader.html) to a new position in the hprof file. This is transparent to the reader, and will not reset [HprofReader.position](../-hprof-reader/position.md). |

### Companion Object Functions

| Name | Summary |
|---|---|
| [open](open.md) | `fun open(hprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`): `[`Hprof`](./index.md)<br>Reads the headers of the provided [hprofFile](open.md#shark.Hprof.Companion$open(java.io.File)/hprofFile) and returns an opened [Hprof](./index.md). Don't forget to call [close](close.md) once done. |
