[shark-hprof](../../index.md) / [shark](../index.md) / [HprofHeader](index.md) / [identifierByteSize](./identifier-byte-size.md)

# identifierByteSize

`val identifierByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)

Size of Hprof identifiers. Identifiers are used to represent UTF8 strings, objects,
stack traces, etc. They can have the same size as host pointers or sizeof(void*), but are not
required to be.

