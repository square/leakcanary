//[shark-graph](../../../index.md)/[shark](../index.md)/[CloseableHeapGraph](index.md)

# CloseableHeapGraph

[jvm]\
interface [CloseableHeapGraph](index.md) : [HeapGraph](../-heap-graph/index.md), [Closeable](https://docs.oracle.com/javase/8/docs/api/java/io/Closeable.html)

A [HeapGraph](../-heap-graph/index.md) that should be closed after being used.

## Functions

| Name | Summary |
|---|---|
| [close](index.md#358956095%2FFunctions%2F-1958818676) | [jvm]<br>abstract override fun [close](index.md#358956095%2FFunctions%2F-1958818676)() |
| [findClassByName](../-heap-graph/find-class-by-name.md) | [jvm]<br>abstract fun [findClassByName](../-heap-graph/find-class-by-name.md)(className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)): [HeapObject.HeapClass](../-heap-object/-heap-class/index.md)?<br>Returns the [HeapClass](../-heap-object/-heap-class/index.md) corresponding to the provided [className](../-heap-graph/find-class-by-name.md), or null if the class cannot be found. |
| [findObjectById](../-heap-graph/find-object-by-id.md) | [jvm]<br>abstract fun [findObjectById](../-heap-graph/find-object-by-id.md)(objectId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)): [HeapObject](../-heap-object/index.md)<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectId](../-heap-graph/find-object-by-id.md), and throws [IllegalArgumentException](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-illegal-argument-exception/index.html) otherwise. |
| [findObjectByIdOrNull](../-heap-graph/find-object-by-id-or-null.md) | [jvm]<br>abstract fun [findObjectByIdOrNull](../-heap-graph/find-object-by-id-or-null.md)(objectId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)): [HeapObject](../-heap-object/index.md)?<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectId](../-heap-graph/find-object-by-id-or-null.md) or null if it cannot be found. |
| [findObjectByIndex](../-heap-graph/find-object-by-index.md) | [jvm]<br>abstract fun [findObjectByIndex](../-heap-graph/find-object-by-index.md)(objectIndex: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [HeapObject](../-heap-object/index.md)<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectIndex](../-heap-graph/find-object-by-index.md), and throws [IllegalArgumentException](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-illegal-argument-exception/index.html) if [objectIndex](../-heap-graph/find-object-by-index.md) is less than 0 or more than [objectCount](../-heap-graph/object-count.md) - 1. |
| [objectExists](../-heap-graph/object-exists.md) | [jvm]<br>abstract fun [objectExists](../-heap-graph/object-exists.md)(objectId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if the provided [objectId](../-heap-graph/object-exists.md) exists in the heap dump. |

## Properties

| Name | Summary |
|---|---|
| [classCount](../-heap-graph/class-count.md) | [jvm]<br>abstract val [classCount](../-heap-graph/class-count.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [classes](../-heap-graph/classes.md) | [jvm]<br>abstract val [classes](../-heap-graph/classes.md): [Sequence](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)&lt;[HeapObject.HeapClass](../-heap-object/-heap-class/index.md)&gt;<br>Sequence of all classes in the heap dump. |
| [context](../-heap-graph/context.md) | [jvm]<br>abstract val [context](../-heap-graph/context.md): [GraphContext](../-graph-context/index.md)<br>In memory store that can be used to store objects this [HeapGraph](../-heap-graph/index.md) instance. |
| [gcRoots](../-heap-graph/gc-roots.md) | [jvm]<br>abstract val [gcRoots](../-heap-graph/gc-roots.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;GcRoot&gt;<br>All GC roots which type matches types known to this heap graph and which point to non null references. You can retrieve the object that a GC Root points to by calling [findObjectById](../-heap-graph/find-object-by-id.md) with GcRoot.id, however you need to first check that [objectExists](../-heap-graph/object-exists.md) returns true because GC roots can point to objects that don't exist in the heap dump. |
| [identifierByteSize](../-heap-graph/identifier-byte-size.md) | [jvm]<br>abstract val [identifierByteSize](../-heap-graph/identifier-byte-size.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [instanceCount](../-heap-graph/instance-count.md) | [jvm]<br>abstract val [instanceCount](../-heap-graph/instance-count.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [instances](../-heap-graph/instances.md) | [jvm]<br>abstract val [instances](../-heap-graph/instances.md): [Sequence](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)&lt;[HeapObject.HeapInstance](../-heap-object/-heap-instance/index.md)&gt;<br>Sequence of all instances in the heap dump. |
| [objectArrayCount](../-heap-graph/object-array-count.md) | [jvm]<br>abstract val [objectArrayCount](../-heap-graph/object-array-count.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [objectArrays](../-heap-graph/object-arrays.md) | [jvm]<br>abstract val [objectArrays](../-heap-graph/object-arrays.md): [Sequence](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)&lt;[HeapObject.HeapObjectArray](../-heap-object/-heap-object-array/index.md)&gt;<br>Sequence of all object arrays in the heap dump. |
| [objectCount](../-heap-graph/object-count.md) | [jvm]<br>abstract val [objectCount](../-heap-graph/object-count.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [objects](../-heap-graph/objects.md) | [jvm]<br>abstract val [objects](../-heap-graph/objects.md): [Sequence](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)&lt;[HeapObject](../-heap-object/index.md)&gt;<br>Sequence of all objects in the heap dump. |
| [primitiveArrayCount](../-heap-graph/primitive-array-count.md) | [jvm]<br>abstract val [primitiveArrayCount](../-heap-graph/primitive-array-count.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [primitiveArrays](../-heap-graph/primitive-arrays.md) | [jvm]<br>abstract val [primitiveArrays](../-heap-graph/primitive-arrays.md): [Sequence](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)&lt;[HeapObject.HeapPrimitiveArray](../-heap-object/-heap-primitive-array/index.md)&gt;<br>Sequence of all primitive arrays in the heap dump. |

## Inheritors

| Name |
|---|
| [HprofHeapGraph](../-hprof-heap-graph/index.md) |
