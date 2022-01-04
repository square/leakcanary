//[shark-graph](../../../../index.md)/[shark](../../index.md)/[HeapObject](../index.md)/[HeapInstance](index.md)/[readField](read-field.md)

# readField

[jvm]\
fun [readField](read-field.md)(declaringClass: [KClass](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)&lt;out [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)&gt;, fieldName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)): [HeapField](../../-heap-field/index.md)?

## See also

jvm

| | |
|---|---|
| [shark.HeapObject.HeapInstance](read-field.md) |  |

[jvm]\
fun [readField](read-field.md)(declaringClassName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), fieldName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)): [HeapField](../../-heap-field/index.md)?

Returns a [HeapField](../../-heap-field/index.md) object that reflects the specified declared field of the instance represented by this [HeapInstance](index.md) object, or null if this field does not exist. The [declaringClassName](read-field.md) specifies the class in which the desired field is declared, and the [fieldName](read-field.md) parameter specifies the simple name of the desired field.

Also available as a convenience operator: [get](get.md)

This may trigger IO reads.
