[shark-hprof](../../index.md) / [shark](../index.md) / [HprofHeader](./index.md)

# HprofHeader

`data class HprofHeader`

Represents the header metadata of a Hprof file.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `HprofHeader(heapDumpTimestamp: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)` = System.currentTimeMillis(), version: `[`HprofVersion`](../-hprof-version/index.md)` = HprofVersion.ANDROID, identifierByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)` = 4)`<br>Represents the header metadata of a Hprof file. |

### Properties

| Name | Summary |
|---|---|
| [heapDumpTimestamp](heap-dump-timestamp.md) | `val heapDumpTimestamp: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Unix timestamp at which the heap was dumped. |
| [identifierByteSize](identifier-byte-size.md) | `val identifierByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>Size of Hprof identifiers. Identifiers are used to represent UTF8 strings, objects, stack traces, etc. They can have the same size as host pointers or sizeof(void*), but are not required to be. |
| [recordsPosition](records-position.md) | `val recordsPosition: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>How many bytes from the beginning of the file can we find the hprof records at. Version string, 0 delimiter (1 byte), identifier byte size int (4 bytes) ,timestamp long (8 bytes) |
| [version](version.md) | `val version: `[`HprofVersion`](../-hprof-version/index.md)<br>Hprof version, which is tied to the runtime where the heap was dumped. |

### Companion Object Functions

| Name | Summary |
|---|---|
| [parseHeaderOf](parse-header-of.md) | `fun parseHeaderOf(hprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`): `[`HprofHeader`](./index.md)<br>Reads the header of the provided [hprofFile](parse-header-of.md#shark.HprofHeader.Companion$parseHeaderOf(java.io.File)/hprofFile) and returns it as a [HprofHeader](./index.md)`fun parseHeaderOf(source: BufferedSource): `[`HprofHeader`](./index.md)<br>Reads the header of the provided [source](parse-header-of.md#shark.HprofHeader.Companion$parseHeaderOf(okio.BufferedSource)/source) and returns it as a [HprofHeader](./index.md). This does not close the [source](parse-header-of.md#shark.HprofHeader.Companion$parseHeaderOf(okio.BufferedSource)/source). |
