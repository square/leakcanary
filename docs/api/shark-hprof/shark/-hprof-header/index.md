//[shark-hprof](../../../index.md)/[shark](../index.md)/[HprofHeader](index.md)

# HprofHeader

[jvm]\
data class [HprofHeader](index.md)(heapDumpTimestamp: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), version: [HprofVersion](../-hprof-version/index.md), identifierByteSize: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html))

Represents the header metadata of a Hprof file.

## Constructors

| | |
|---|---|
| [HprofHeader](-hprof-header.md) | [jvm]<br>fun [HprofHeader](-hprof-header.md)(heapDumpTimestamp: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) = System.currentTimeMillis(), version: [HprofVersion](../-hprof-version/index.md) = HprofVersion.ANDROID, identifierByteSize: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) = 4) |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Properties

| Name | Summary |
|---|---|
| [heapDumpTimestamp](heap-dump-timestamp.md) | [jvm]<br>val [heapDumpTimestamp](heap-dump-timestamp.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Unix timestamp at which the heap was dumped. |
| [identifierByteSize](identifier-byte-size.md) | [jvm]<br>val [identifierByteSize](identifier-byte-size.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) = 4<br>Size of Hprof identifiers. Identifiers are used to represent UTF8 strings, objects, stack traces, etc. They can have the same size as host pointers or sizeof(void*), but are not required to be. |
| [recordsPosition](records-position.md) | [jvm]<br>val [recordsPosition](records-position.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>How many bytes from the beginning of the file can we find the hprof records at. Version string, 0 delimiter (1 byte), identifier byte size int (4 bytes) ,timestamp long (8 bytes) |
| [version](version.md) | [jvm]<br>val [version](version.md): [HprofVersion](../-hprof-version/index.md)<br>Hprof version, which is tied to the runtime where the heap was dumped. |
