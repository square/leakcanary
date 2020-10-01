[shark-graph](../../index.md) / [shark](../index.md) / [HprofHeapGraph](./index.md)

# HprofHeapGraph

`class HprofHeapGraph : `[`CloseableHeapGraph`](../-closeable-heap-graph.md)

A [HeapGraph](../-heap-graph/index.md) that reads from an Hprof file indexed by [HprofIndex](../-hprof-index/index.md).

### Properties

| Name | Summary |
|---|---|
| [classCount](class-count.md) | `val classCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [classes](classes.md) | `val classes: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapClass`](../-heap-object/-heap-class/index.md)`>`<br>Sequence of all classes in the heap dump. |
| [context](context.md) | `val context: `[`GraphContext`](../-graph-context/index.md)<br>In memory store that can be used to store objects this [HeapGraph](../-heap-graph/index.md) instance. |
| [gcRoots](gc-roots.md) | `val gcRoots: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<GcRoot>`<br>All GC roots which type matches types known to this heap graph and which point to non null references. You can retrieve the object that a GC Root points to by calling [findObjectById](../-heap-graph/find-object-by-id.md) with [GcRoot.id](#), however you need to first check that [objectExists](../-heap-graph/object-exists.md) returns true because GC roots can point to objects that don't exist in the heap dump. |
| [identifierByteSize](identifier-byte-size.md) | `val identifierByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [instanceCount](instance-count.md) | `val instanceCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [instances](instances.md) | `val instances: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapInstance`](../-heap-object/-heap-instance/index.md)`>`<br>Sequence of all instances in the heap dump. |
| [objectArrayCount](object-array-count.md) | `val objectArrayCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [objectArrays](object-arrays.md) | `val objectArrays: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapObjectArray`](../-heap-object/-heap-object-array/index.md)`>`<br>Sequence of all object arrays in the heap dump. |
| [objectCount](object-count.md) | `val objectCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [objects](objects.md) | `val objects: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject`](../-heap-object/index.md)`>`<br>Sequence of all objects in the heap dump. |
| [primitiveArrayCount](primitive-array-count.md) | `val primitiveArrayCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [primitiveArrays](primitive-arrays.md) | `val primitiveArrays: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapPrimitiveArray`](../-heap-object/-heap-primitive-array/index.md)`>`<br>Sequence of all primitive arrays in the heap dump. |

### Functions

| Name | Summary |
|---|---|
| [close](close.md) | `fun close(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [findClassByName](find-class-by-name.md) | `fun findClassByName(className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`HeapObject.HeapClass`](../-heap-object/-heap-class/index.md)`?`<br>Returns the [HeapClass](../-heap-object/-heap-class/index.md) corresponding to the provided [className](../-heap-graph/find-class-by-name.md#shark.HeapGraph$findClassByName(kotlin.String)/className), or null if the class cannot be found. |
| [findObjectById](find-object-by-id.md) | `fun findObjectById(objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`HeapObject`](../-heap-object/index.md)<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectId](../-heap-graph/find-object-by-id.md#shark.HeapGraph$findObjectById(kotlin.Long)/objectId), and throws [IllegalArgumentException](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-illegal-argument-exception/index.html) otherwise. |
| [findObjectByIdOrNull](find-object-by-id-or-null.md) | `fun findObjectByIdOrNull(objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`HeapObject`](../-heap-object/index.md)`?`<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectId](../-heap-graph/find-object-by-id-or-null.md#shark.HeapGraph$findObjectByIdOrNull(kotlin.Long)/objectId) or null if it cannot be found. |
| [findObjectByIndex](find-object-by-index.md) | `fun findObjectByIndex(objectIndex: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`HeapObject`](../-heap-object/index.md)<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectIndex](../-heap-graph/find-object-by-index.md#shark.HeapGraph$findObjectByIndex(kotlin.Int)/objectIndex), and throws [IllegalArgumentException](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-illegal-argument-exception/index.html) if [objectIndex](../-heap-graph/find-object-by-index.md#shark.HeapGraph$findObjectByIndex(kotlin.Int)/objectIndex) is less than 0 or more than [objectCount](../-heap-graph/object-count.md) - 1. |
| [lruCacheStats](lru-cache-stats.md) | `fun lruCacheStats(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>This is only public so that we can publish stats. Accessing this requires casting [HeapGraph](../-heap-graph/index.md) to [HprofHeapGraph](./index.md) so it's really not a public API. May change at any time! |
| [objectExists](object-exists.md) | `fun objectExists(objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if the provided [objectId](../-heap-graph/object-exists.md#shark.HeapGraph$objectExists(kotlin.Long)/objectId) exists in the heap dump. |

### Companion Object Properties

| Name | Summary |
|---|---|
| [INTERNAL_LRU_CACHE_SIZE](-i-n-t-e-r-n-a-l_-l-r-u_-c-a-c-h-e_-s-i-z-e.md) | `var INTERNAL_LRU_CACHE_SIZE: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>This is not a public API, it's only public so that we can evaluate the effectiveness of different cache size in tests in a different module. |

### Companion Object Functions

| Name | Summary |
|---|---|
| [indexHprof](index-hprof.md) | `fun ~~indexHprof~~(hprof: Hprof, proguardMapping: ProguardMapping? = null, indexedGcRootTypes: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<out GcRoot>> = deprecatedDefaultIndexedGcRootTypes()): `[`HeapGraph`](../-heap-graph/index.md) |
| [openHeapGraph](open-heap-graph.md) | `fun `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`.openHeapGraph(proguardMapping: ProguardMapping? = null, indexedGcRootTypes: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<HprofRecordTag> = HprofIndex.defaultIndexedGcRootTags()): `[`CloseableHeapGraph`](../-closeable-heap-graph.md)<br>A facility for opening a [CloseableHeapGraph](../-closeable-heap-graph.md) from a [File](https://docs.oracle.com/javase/6/docs/api/java/io/File.html). This first parses the file headers with [HprofHeader.parseHeaderOf](#), then indexes the file content with [HprofIndex.indexRecordsOf](../-hprof-index/index-records-of.md) and then opens a [CloseableHeapGraph](../-closeable-heap-graph.md) from the index, which you are responsible for closing after using.`fun DualSourceProvider.openHeapGraph(proguardMapping: ProguardMapping? = null, indexedGcRootTypes: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<HprofRecordTag> = HprofIndex.defaultIndexedGcRootTags()): `[`CloseableHeapGraph`](../-closeable-heap-graph.md) |
