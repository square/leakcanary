[shark-graph](../../../index.md) / [shark](../../index.md) / [HeapObject](../index.md) / [HeapClass](index.md) / [readFieldsByteSize](./read-fields-byte-size.md)

# readFieldsByteSize

`fun readFieldsByteSize(): `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)

The total byte size of fields for instances of this class, computed as the sum of the
individual size of each field of this class. This does not include the size of fields from
superclasses.

This may trigger IO reads.

**See Also**

[instanceByteSize](instance-byte-size.md)

