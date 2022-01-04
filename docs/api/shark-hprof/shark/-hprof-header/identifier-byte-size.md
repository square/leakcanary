//[shark-hprof](../../../index.md)/[shark](../index.md)/[HprofHeader](index.md)/[identifierByteSize](identifier-byte-size.md)

# identifierByteSize

[jvm]\
val [identifierByteSize](identifier-byte-size.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) = 4

Size of Hprof identifiers. Identifiers are used to represent UTF8 strings, objects, stack traces, etc. They can have the same size as host pointers or sizeof(void*), but are not required to be.
