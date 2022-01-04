//[shark-graph](../../../index.md)/[shark](../index.md)/[HprofHeapGraph](index.md)

# HprofHeapGraph

[jvm]\
class [HprofHeapGraph](index.md) : [CloseableHeapGraph](../-closeable-heap-graph/index.md)

A [HeapGraph](../-heap-graph/index.md) that reads from an Hprof file indexed by [HprofIndex](../-hprof-index/index.md).

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [close](close.md) | [jvm]<br>open override fun [close](close.md)() |
| [findClassByName](find-class-by-name.md) | [jvm]<br>open override fun [findClassByName](find-class-by-name.md)(className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)): [HeapObject.HeapClass](../-heap-object/-heap-class/index.md)?<br>Returns the [HeapClass](../-heap-object/-heap-class/index.md) corresponding to the provided [className](find-class-by-name.md), or null if the class cannot be found. |
| [findObjectById](find-object-by-id.md) | [jvm]<br>open override fun [findObjectById](find-object-by-id.md)(objectId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)): [HeapObject](../-heap-object/index.md)<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectId](find-object-by-id.md), and throws [IllegalArgumentException](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-illegal-argument-exception/index.html) otherwise. |
| [findObjectByIdOrNull](find-object-by-id-or-null.md) | [jvm]<br>open override fun [findObjectByIdOrNull](find-object-by-id-or-null.md)(objectId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)): [HeapObject](../-heap-object/index.md)?<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectId](find-object-by-id-or-null.md) or null if it cannot be found. |
| [findObjectByIndex](find-object-by-index.md) | [jvm]<br>open override fun [findObjectByIndex](find-object-by-index.md)(objectIndex: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [HeapObject](../-heap-object/index.md)<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectIndex](find-object-by-index.md), and throws [IllegalArgumentException](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-illegal-argument-exception/index.html) if [objectIndex](find-object-by-index.md) is less than 0 or more than [objectCount](object-count.md) - 1. |
| [lruCacheStats](lru-cache-stats.md) | [jvm]<br>fun [lruCacheStats](lru-cache-stats.md)(): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>This is only public so that we can publish stats. Accessing this requires casting [HeapGraph](../-heap-graph/index.md) to [HprofHeapGraph](index.md) so it's really not a public API. May change at any time! |
| [objectExists](object-exists.md) | [jvm]<br>open override fun [objectExists](object-exists.md)(objectId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if the provided [objectId](object-exists.md) exists in the heap dump. |

## Properties

| Name | Summary |
|---|---|
| [classCount](class-count.md) | [jvm]<br>open override val [classCount](class-count.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [classes](classes.md) | [jvm]<br>open override val [classes](classes.md): [Sequence](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)&lt;[HeapObject.HeapClass](../-heap-object/-heap-class/index.md)&gt;<br>Sequence of all classes in the heap dump. |
| [context](context.md) | [jvm]<br>open override val [context](context.md): [GraphContext](../-graph-context/index.md)<br>In memory store that can be used to store objects this [HeapGraph](../-heap-graph/index.md) instance. |
| [gcRoots](gc-roots.md) | [jvm]<br>open override val [gcRoots](gc-roots.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;GcRoot&gt;<br>All GC roots which type matches types known to this heap graph and which point to non null references. You can retrieve the object that a GC Root points to by calling [findObjectById](find-object-by-id.md) with GcRoot.id, however you need to first check that [objectExists](object-exists.md) returns true because GC roots can point to objects that don't exist in the heap dump. |
| [identifierByteSize](identifier-byte-size.md) | [jvm]<br>open override val [identifierByteSize](identifier-byte-size.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [instanceCount](instance-count.md) | [jvm]<br>open override val [instanceCount](instance-count.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [instances](instances.md) | [jvm]<br>open override val [instances](instances.md): [Sequence](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)&lt;[HeapObject.HeapInstance](../-heap-object/-heap-instance/index.md)&gt;<br>Sequence of all instances in the heap dump. |
| [objectArrayCount](object-array-count.md) | [jvm]<br>open override val [objectArrayCount](object-array-count.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [objectArrays](object-arrays.md) | [jvm]<br>open override val [objectArrays](object-arrays.md): [Sequence](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)&lt;[HeapObject.HeapObjectArray](../-heap-object/-heap-object-array/index.md)&gt;<br>Sequence of all object arrays in the heap dump. |
| [objectCount](object-count.md) | [jvm]<br>open override val [objectCount](object-count.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [objects](objects.md) | [jvm]<br>open override val [objects](objects.md): [Sequence](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)&lt;[HeapObject](../-heap-object/index.md)&gt;<br>Sequence of all objects in the heap dump. |
| [primitiveArrayCount](primitive-array-count.md) | [jvm]<br>open override val [primitiveArrayCount](primitive-array-count.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [primitiveArrays](primitive-arrays.md) | [jvm]<br>open override val [primitiveArrays](primitive-arrays.md): [Sequence](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)&lt;[HeapObject.HeapPrimitiveArray](../-heap-object/-heap-primitive-array/index.md)&gt;<br>Sequence of all primitive arrays in the heap dump. |
