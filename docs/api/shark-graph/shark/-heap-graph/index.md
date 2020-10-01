[shark-graph](../../index.md) / [shark](../index.md) / [HeapGraph](./index.md)

# HeapGraph

`interface HeapGraph`

Enables navigation through the heap graph of objects.

### Properties

| Name | Summary |
|---|---|
| [classCount](class-count.md) | `abstract val classCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [classes](classes.md) | `abstract val classes: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapClass`](../-heap-object/-heap-class/index.md)`>`<br>Sequence of all classes in the heap dump. |
| [context](context.md) | `abstract val context: `[`GraphContext`](../-graph-context/index.md)<br>In memory store that can be used to store objects this [HeapGraph](./index.md) instance. |
| [gcRoots](gc-roots.md) | `abstract val gcRoots: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<GcRoot>`<br>All GC roots which type matches types known to this heap graph and which point to non null references. You can retrieve the object that a GC Root points to by calling [findObjectById](find-object-by-id.md) with [GcRoot.id](#), however you need to first check that [objectExists](object-exists.md) returns true because GC roots can point to objects that don't exist in the heap dump. |
| [identifierByteSize](identifier-byte-size.md) | `abstract val identifierByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [instanceCount](instance-count.md) | `abstract val instanceCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [instances](instances.md) | `abstract val instances: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapInstance`](../-heap-object/-heap-instance/index.md)`>`<br>Sequence of all instances in the heap dump. |
| [objectArrayCount](object-array-count.md) | `abstract val objectArrayCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [objectArrays](object-arrays.md) | `abstract val objectArrays: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapObjectArray`](../-heap-object/-heap-object-array/index.md)`>`<br>Sequence of all object arrays in the heap dump. |
| [objectCount](object-count.md) | `abstract val objectCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [objects](objects.md) | `abstract val objects: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject`](../-heap-object/index.md)`>`<br>Sequence of all objects in the heap dump. |
| [primitiveArrayCount](primitive-array-count.md) | `abstract val primitiveArrayCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [primitiveArrays](primitive-arrays.md) | `abstract val primitiveArrays: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapPrimitiveArray`](../-heap-object/-heap-primitive-array/index.md)`>`<br>Sequence of all primitive arrays in the heap dump. |

### Functions

| Name | Summary |
|---|---|
| [findClassByName](find-class-by-name.md) | `abstract fun findClassByName(className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`HeapObject.HeapClass`](../-heap-object/-heap-class/index.md)`?`<br>Returns the [HeapClass](../-heap-object/-heap-class/index.md) corresponding to the provided [className](find-class-by-name.md#shark.HeapGraph$findClassByName(kotlin.String)/className), or null if the class cannot be found. |
| [findObjectById](find-object-by-id.md) | `abstract fun findObjectById(objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`HeapObject`](../-heap-object/index.md)<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectId](find-object-by-id.md#shark.HeapGraph$findObjectById(kotlin.Long)/objectId), and throws [IllegalArgumentException](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-illegal-argument-exception/index.html) otherwise. |
| [findObjectByIdOrNull](find-object-by-id-or-null.md) | `abstract fun findObjectByIdOrNull(objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`HeapObject`](../-heap-object/index.md)`?`<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectId](find-object-by-id-or-null.md#shark.HeapGraph$findObjectByIdOrNull(kotlin.Long)/objectId) or null if it cannot be found. |
| [findObjectByIndex](find-object-by-index.md) | `abstract fun findObjectByIndex(objectIndex: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`HeapObject`](../-heap-object/index.md)<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectIndex](find-object-by-index.md#shark.HeapGraph$findObjectByIndex(kotlin.Int)/objectIndex), and throws [IllegalArgumentException](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-illegal-argument-exception/index.html) if [objectIndex](find-object-by-index.md#shark.HeapGraph$findObjectByIndex(kotlin.Int)/objectIndex) is less than 0 or more than [objectCount](object-count.md) - 1. |
| [objectExists](object-exists.md) | `abstract fun objectExists(objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if the provided [objectId](object-exists.md#shark.HeapGraph$objectExists(kotlin.Long)/objectId) exists in the heap dump. |

### Inheritors

| Name | Summary |
|---|---|
| [CloseableHeapGraph](../-closeable-heap-graph.md) | `interface CloseableHeapGraph : `[`HeapGraph`](./index.md)`, `[`Closeable`](https://docs.oracle.com/javase/6/docs/api/java/io/Closeable.html)<br>A [HeapGraph](./index.md) that should be closed after being used. |
