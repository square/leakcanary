[shark-graph](../../index.md) / [shark](../index.md) / [HeapField](./index.md)

# HeapField

`class HeapField`

Represents a static field or an instance field.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `HeapField(declaringClass: `[`HeapObject.HeapClass`](../-heap-object/-heap-class/index.md)`, name: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, value: `[`HeapValue`](../-heap-value/index.md)`)`<br>Represents a static field or an instance field. |

### Properties

| Name | Summary |
|---|---|
| [declaringClass](declaring-class.md) | `val declaringClass: `[`HeapObject.HeapClass`](../-heap-object/-heap-class/index.md)<br>The class this field was declared in. |
| [name](name.md) | `val name: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Name of the field |
| [value](value.md) | `val value: `[`HeapValue`](../-heap-value/index.md)<br>Value of the field. Also see shorthands [valueAsClass](value-as-class.md), [valueAsInstance](value-as-instance.md), [valueAsObjectArray](value-as-object-array.md), [valueAsPrimitiveArray](value-as-primitive-array.md). |
| [valueAsClass](value-as-class.md) | `val valueAsClass: `[`HeapObject.HeapClass`](../-heap-object/-heap-class/index.md)`?`<br>Return a [HeapClass](../-heap-object/-heap-class/index.md) is [value](value.md) references a class, and null otherwise. |
| [valueAsInstance](value-as-instance.md) | `val valueAsInstance: `[`HeapObject.HeapInstance`](../-heap-object/-heap-instance/index.md)`?`<br>Return a [HeapInstance](../-heap-object/-heap-instance/index.md) is [value](value.md) references an instance, and null otherwise. |
| [valueAsObjectArray](value-as-object-array.md) | `val valueAsObjectArray: `[`HeapObject.HeapObjectArray`](../-heap-object/-heap-object-array/index.md)`?`<br>Return a [HeapObjectArray](../-heap-object/-heap-object-array/index.md) is [value](value.md) references an object array, and null otherwise. |
| [valueAsPrimitiveArray](value-as-primitive-array.md) | `val valueAsPrimitiveArray: `[`HeapObject.HeapPrimitiveArray`](../-heap-object/-heap-primitive-array/index.md)`?`<br>Return a [HeapPrimitiveArray](../-heap-object/-heap-primitive-array/index.md) is [value](value.md) references a primitive array, and null otherwise. |
