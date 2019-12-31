[shark-graph](../../index.md) / [shark](../index.md) / [HeapGraph](./index.md)

# HeapGraph

`interface HeapGraph`

Enables navigation through the heap graph of objects.

### Properties

| Name | Summary |
|---|---|
| [classes](classes.md) | `abstract val classes: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapClass`](../-heap-object/-heap-class/index.md)`>`<br>Sequence of all classes in the heap dump. |
| [context](context.md) | `abstract val context: `[`GraphContext`](../-graph-context/index.md)<br>In memory store that can be used to store objects this [HeapGraph](./index.md) instance. |
| [gcRoots](gc-roots.md) | `abstract val gcRoots: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<GcRoot>`<br>All GC roots which type matches types known to this heap graph and which point to non null references. You can retrieve the object that a GC Root points to by calling [findObjectById](find-object-by-id.md) with [GcRoot.id](#), however you need to first check that [objectExists](object-exists.md) returns true because GC roots can point to objects that don't exist in the heap dump. |
| [identifierByteSize](identifier-byte-size.md) | `abstract val identifierByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [instances](instances.md) | `abstract val instances: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapInstance`](../-heap-object/-heap-instance/index.md)`>`<br>Sequence of all instances in the heap dump. |
| [objectArrays](object-arrays.md) | `abstract val objectArrays: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapObjectArray`](../-heap-object/-heap-object-array/index.md)`>`<br>Sequence of all object arrays in the heap dump. |
| [objects](objects.md) | `abstract val objects: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject`](../-heap-object/index.md)`>`<br>Sequence of all objects in the heap dump. |
| [primitiveArrays](primitive-arrays.md) | `abstract val primitiveArrays: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapPrimitiveArray`](../-heap-object/-heap-primitive-array/index.md)`>`<br>Sequence of all primitive arrays in the heap dump. |

### Functions

| Name | Summary |
|---|---|
| [findClassByName](find-class-by-name.md) | `abstract fun findClassByName(className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`HeapObject.HeapClass`](../-heap-object/-heap-class/index.md)`?`<br>Returns the [HeapClass](../-heap-object/-heap-class/index.md) corresponding to the provided [className](find-class-by-name.md#shark.HeapGraph$findClassByName(kotlin.String)/className), or null if the class cannot be found. |
| [findObjectById](find-object-by-id.md) | `abstract fun findObjectById(objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`HeapObject`](../-heap-object/index.md)<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectId](find-object-by-id.md#shark.HeapGraph$findObjectById(kotlin.Long)/objectId), and throws [IllegalArgumentException](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-illegal-argument-exception/index.html) otherwise. |
| [findObjectByIdOrNull](find-object-by-id-or-null.md) | `abstract fun findObjectByIdOrNull(objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`HeapObject`](../-heap-object/index.md)`?`<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectId](find-object-by-id-or-null.md#shark.HeapGraph$findObjectByIdOrNull(kotlin.Long)/objectId) or null if it cannot be found. |
| [objectExists](object-exists.md) | `abstract fun objectExists(objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if the provided [objectId](object-exists.md#shark.HeapGraph$objectExists(kotlin.Long)/objectId) exists in the heap dump. |

### Inheritors

| Name | Summary |
|---|---|
| [HprofHeapGraph](../-hprof-heap-graph/index.md) | `class HprofHeapGraph : `[`HeapGraph`](./index.md)<br>A [HeapGraph](./index.md) that reads from an indexed [Hprof](#). Create a new instance with [indexHprof](../-hprof-heap-graph/index-hprof.md). |
