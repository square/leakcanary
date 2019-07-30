[shark-graph](../../../index.md) / [shark](../../index.md) / [HeapObject](../index.md) / [HeapClass](index.md) / [readStaticField](./read-static-field.md)

# readStaticField

`fun readStaticField(fieldName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`HeapField`](../../-heap-field/index.md)`?`

Returns a [HeapField](../../-heap-field/index.md) object that reflects the specified declared
field of the class represented by this [HeapClass](index.md) object, or null if this field does not
exist. The [name](name.md) parameter specifies the simple name of the desired field.

Also available as a convenience operator: [get](get.md)

This may trigger IO reads.

