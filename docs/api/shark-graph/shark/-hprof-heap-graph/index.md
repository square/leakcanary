[shark-graph](../../index.md) / [shark](../index.md) / [HprofHeapGraph](./index.md)

# HprofHeapGraph

`class HprofHeapGraph : `[`HeapGraph`](../-heap-graph/index.md)

A [HeapGraph](../-heap-graph/index.md) that reads from an indexed [Hprof](#). Create a new instance with [indexHprof](index-hprof.md).

### Properties

| Name | Summary |
|---|---|
| [classes](classes.md) | `val classes: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapClass`](../-heap-object/-heap-class/index.md)`>`<br>Sequence of all classes in the heap dump. |
| [context](context.md) | `val context: `[`GraphContext`](../-graph-context/index.md)<br>In memory store that can be used to store objects this [HeapGraph](../-heap-graph/index.md) instance. |
| [gcRoots](gc-roots.md) | `val gcRoots: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<GcRoot>`<br>All GC roots which type matches types known to this heap graph and which point to non null references. You can retrieve the object that a GC Root points to by calling [findObjectById](../-heap-graph/find-object-by-id.md) with [GcRoot.id](#), however you need to first check that [objectExists](../-heap-graph/object-exists.md) returns true because GC roots can point to objects that don't exist in the heap dump. |
| [identifierByteSize](identifier-byte-size.md) | `val identifierByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [instances](instances.md) | `val instances: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapInstance`](../-heap-object/-heap-instance/index.md)`>`<br>Sequence of all instances in the heap dump. |
| [objectArrays](object-arrays.md) | `val objectArrays: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapObjectArray`](../-heap-object/-heap-object-array/index.md)`>`<br>Sequence of all object arrays in the heap dump. |
| [objects](objects.md) | `val objects: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject`](../-heap-object/index.md)`>`<br>Sequence of all objects in the heap dump. |
| [primitiveArrays](primitive-arrays.md) | `val primitiveArrays: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapPrimitiveArray`](../-heap-object/-heap-primitive-array/index.md)`>`<br>Sequence of all primitive arrays in the heap dump. |

### Functions

| Name | Summary |
|---|---|
| [findClassByName](find-class-by-name.md) | `fun findClassByName(className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`HeapObject.HeapClass`](../-heap-object/-heap-class/index.md)`?`<br>Returns the [HeapClass](../-heap-object/-heap-class/index.md) corresponding to the provided [className](../-heap-graph/find-class-by-name.md#shark.HeapGraph$findClassByName(kotlin.String)/className), or null if the class cannot be found. |
| [findObjectById](find-object-by-id.md) | `fun findObjectById(objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`HeapObject`](../-heap-object/index.md)<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectId](../-heap-graph/find-object-by-id.md#shark.HeapGraph$findObjectById(kotlin.Long)/objectId), and throws [IllegalArgumentException](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-illegal-argument-exception/index.html) otherwise. |
| [findObjectByIdOrNull](find-object-by-id-or-null.md) | `fun findObjectByIdOrNull(objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`HeapObject`](../-heap-object/index.md)`?`<br>Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectId](../-heap-graph/find-object-by-id-or-null.md#shark.HeapGraph$findObjectByIdOrNull(kotlin.Long)/objectId) or null if it cannot be found. |
| [objectExists](object-exists.md) | `fun objectExists(objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if the provided [objectId](../-heap-graph/object-exists.md#shark.HeapGraph$objectExists(kotlin.Long)/objectId) exists in the heap dump. |

### Companion Object Functions

| Name | Summary |
|---|---|
| [indexHprof](index-hprof.md) | `fun indexHprof(hprof: Hprof, proguardMapping: `[`ProguardMapping`](../-proguard-mapping/index.md)`? = null, indexedGcRootTypes: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<out GcRoot>> = setOf(
          JniGlobal::class,
          JavaFrame::class,
          JniLocal::class,
          MonitorUsed::class,
          NativeStack::class,
          StickyClass::class,
          ThreadBlock::class,
          // ThreadObject points to threads, which we need to find the thread that a JavaLocalPattern
          // belongs to
          ThreadObject::class,
          JniMonitor::class
          /*
          Not included here:

          VmInternal: Ignoring because we've got 150K of it, but is this the right thing
          to do? What's VmInternal exactly? History does not go further than
          https://android.googlesource.com/platform/dalvik2/+/refs/heads/master/hit/src/com/android/hit/HprofParser.java#77
          We should log to figure out what objects VmInternal points to.

          ReferenceCleanup: We used to keep it, but the name doesn't seem like it should create a leak.

          Unknown: it's unknown, should we care?

          We definitely don't care about those for leak finding: InternedString, Finalizing, Debugger, Unreachable
           */
      )): `[`HeapGraph`](../-heap-graph/index.md) |
