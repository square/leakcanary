[shark-graph](../../../index.md) / [shark](../../index.md) / [HeapObject](../index.md) / [HeapInstance](./index.md)

# HeapInstance

`class HeapInstance : `[`HeapObject`](../index.md)

An instance in the heap dump.

### Properties

| Name | Summary |
|---|---|
| [byteSize](byte-size.md) | `val byteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [graph](graph.md) | `val graph: `[`HeapGraph`](../../-heap-graph/index.md)<br>The graph of objects in the heap, which you can use to navigate the heap. |
| [instanceClass](instance-class.md) | `val instanceClass: `[`HeapObject.HeapClass`](../-heap-class/index.md)<br>The class of this instance. |
| [instanceClassId](instance-class-id.md) | `val instanceClassId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The heap identifier of the class of this instance. |
| [instanceClassName](instance-class-name.md) | `val instanceClassName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>The name of the class of this instance, identical to [Class.getName](https://docs.oracle.com/javase/6/docs/api/java/lang/Class.html#getName()). |
| [instanceClassSimpleName](instance-class-simple-name.md) | `val instanceClassSimpleName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Returns [instanceClassName](instance-class-name.md) stripped of any string content before the last period (included). |
| [isPrimitiveWrapper](is-primitive-wrapper.md) | `val isPrimitiveWrapper: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether this is an instance of a primitive wrapper type. |
| [objectId](object-id.md) | `val objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The heap identifier of this object. |
| [objectIndex](object-index.md) | `val objectIndex: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>An positive object index that's specific to how Shark stores objects in memory. The index starts at 0 and ends at [HeapGraph.objectCount](../../-heap-graph/object-count.md) - 1. There are no gaps, every index value corresponds to an object. Classes are first, then instances, then object arrays then primitive arrays. |
| [recordSize](record-size.md) | `val recordSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>The total byte size for the record of this object in the heap dump. |

### Inherited Properties

| Name | Summary |
|---|---|
| [asClass](../as-class.md) | `val asClass: `[`HeapObject.HeapClass`](../-heap-class/index.md)`?`<br>This [HeapObject](../index.md) as a [HeapClass](../-heap-class/index.md) if it is one, or null otherwise |
| [asInstance](../as-instance.md) | `val asInstance: `[`HeapObject.HeapInstance`](./index.md)`?`<br>This [HeapObject](../index.md) as a [HeapInstance](./index.md) if it is one, or null otherwise |
| [asObjectArray](../as-object-array.md) | `val asObjectArray: `[`HeapObject.HeapObjectArray`](../-heap-object-array/index.md)`?`<br>This [HeapObject](../index.md) as a [HeapObjectArray](../-heap-object-array/index.md) if it is one, or null otherwise |
| [asPrimitiveArray](../as-primitive-array.md) | `val asPrimitiveArray: `[`HeapObject.HeapPrimitiveArray`](../-heap-primitive-array/index.md)`?`<br>This [HeapObject](../index.md) as a [HeapPrimitiveArray](../-heap-primitive-array/index.md) if it is one, or null otherwise |

### Functions

| Name | Summary |
|---|---|
| [get](get.md) | `operator fun get(declaringClass: `[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<out `[`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)`>, fieldName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`HeapField`](../../-heap-field/index.md)`?`<br>`operator fun get(declaringClassName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, fieldName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`HeapField`](../../-heap-field/index.md)`?` |
| [instanceOf](instance-of.md) | `infix fun instanceOf(className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if this is an instance of the class named [className](instance-of.md#shark.HeapObject.HeapInstance$instanceOf(kotlin.String)/className) or an instance of a subclass of that class.`infix fun instanceOf(expectedClass: `[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<*>): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>`infix fun instanceOf(expectedClass: `[`HeapObject.HeapClass`](../-heap-class/index.md)`): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if this is an instance of [expectedClass](instance-of.md#shark.HeapObject.HeapInstance$instanceOf(kotlin.reflect.KClass((kotlin.Any)))/expectedClass) or an instance of a subclass of that class. |
| [readAsJavaString](read-as-java-string.md) | `fun readAsJavaString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`?`<br>If this [HeapInstance](./index.md) is an instance of the [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) class, returns a [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) instance with content that matches the string in the heap dump. Otherwise returns null. |
| [readField](read-field.md) | `fun readField(declaringClass: `[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<out `[`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)`>, fieldName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`HeapField`](../../-heap-field/index.md)`?``fun readField(declaringClassName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, fieldName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`HeapField`](../../-heap-field/index.md)`?`<br>Returns a [HeapField](../../-heap-field/index.md) object that reflects the specified declared field of the instance represented by this [HeapInstance](./index.md) object, or null if this field does not exist. The [declaringClassName](read-field.md#shark.HeapObject.HeapInstance$readField(kotlin.String, kotlin.String)/declaringClassName) specifies the class in which the desired field is declared, and the [fieldName](read-field.md#shark.HeapObject.HeapInstance$readField(kotlin.String, kotlin.String)/fieldName) parameter specifies the simple name of the desired field. |
| [readFields](read-fields.md) | `fun readFields(): `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapField`](../../-heap-field/index.md)`>`<br>The fields of this instance, as a sequence of [HeapField](../../-heap-field/index.md). |
| [readRecord](read-record.md) | `fun readRecord(): InstanceDumpRecord`<br>Reads and returns the underlying [InstanceDumpRecord](#). |
| [toString](to-string.md) | `fun toString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
