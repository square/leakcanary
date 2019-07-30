[shark-graph](../../../index.md) / [shark](../../index.md) / [HeapObject](../index.md) / [HeapInstance](index.md) / [readField](./read-field.md)

# readField

`fun readField(declaringClass: `[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<out `[`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)`>, fieldName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`HeapField`](../../-heap-field/index.md)`?`

**See Also**

[readField](./read-field.md)

`fun readField(declaringClassName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, fieldName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`HeapField`](../../-heap-field/index.md)`?`

Returns a [HeapField](../../-heap-field/index.md) object that reflects the specified declared
field of the instance represented by this [HeapInstance](index.md) object, or null if this field does
not exist. The [declaringClassName](read-field.md#shark.HeapObject.HeapInstance$readField(kotlin.String, kotlin.String)/declaringClassName) specifies the class in which the desired field is
declared, and the [fieldName](read-field.md#shark.HeapObject.HeapInstance$readField(kotlin.String, kotlin.String)/fieldName) parameter specifies the simple name of the desired field.

Also available as a convenience operator: [get](get.md)

This may trigger IO reads.

