//[shark-graph](../../../index.md)/[shark](../index.md)/[HeapField](index.md)

# HeapField

[jvm]\
class [HeapField](index.md)(declaringClass: [HeapObject.HeapClass](../-heap-object/-heap-class/index.md), name: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), value: [HeapValue](../-heap-value/index.md))

Represents a static field or an instance field.

## Constructors

| | |
|---|---|
| [HeapField](-heap-field.md) | [jvm]<br>fun [HeapField](-heap-field.md)(declaringClass: [HeapObject.HeapClass](../-heap-object/-heap-class/index.md), name: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), value: [HeapValue](../-heap-value/index.md)) |

## Properties

| Name | Summary |
|---|---|
| [declaringClass](declaring-class.md) | [jvm]<br>val [declaringClass](declaring-class.md): [HeapObject.HeapClass](../-heap-object/-heap-class/index.md)<br>The class this field was declared in. |
| [name](name.md) | [jvm]<br>val [name](name.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Name of the field |
| [value](value.md) | [jvm]<br>val [value](value.md): [HeapValue](../-heap-value/index.md)<br>Value of the field. Also see shorthands [valueAsClass](value-as-class.md), [valueAsInstance](value-as-instance.md), [valueAsObjectArray](value-as-object-array.md), [valueAsPrimitiveArray](value-as-primitive-array.md). |
| [valueAsClass](value-as-class.md) | [jvm]<br>val [valueAsClass](value-as-class.md): [HeapObject.HeapClass](../-heap-object/-heap-class/index.md)?<br>Return a [HeapClass](../-heap-object/-heap-class/index.md) is [value](value.md) references a class, and null otherwise. |
| [valueAsInstance](value-as-instance.md) | [jvm]<br>val [valueAsInstance](value-as-instance.md): [HeapObject.HeapInstance](../-heap-object/-heap-instance/index.md)?<br>Return a [HeapInstance](../-heap-object/-heap-instance/index.md) is [value](value.md) references an instance, and null otherwise. |
| [valueAsObjectArray](value-as-object-array.md) | [jvm]<br>val [valueAsObjectArray](value-as-object-array.md): [HeapObject.HeapObjectArray](../-heap-object/-heap-object-array/index.md)?<br>Return a [HeapObjectArray](../-heap-object/-heap-object-array/index.md) is [value](value.md) references an object array, and null otherwise. |
| [valueAsPrimitiveArray](value-as-primitive-array.md) | [jvm]<br>val [valueAsPrimitiveArray](value-as-primitive-array.md): [HeapObject.HeapPrimitiveArray](../-heap-object/-heap-primitive-array/index.md)?<br>Return a [HeapPrimitiveArray](../-heap-object/-heap-primitive-array/index.md) is [value](value.md) references a primitive array, and null otherwise. |
