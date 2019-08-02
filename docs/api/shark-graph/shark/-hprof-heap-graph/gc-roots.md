[shark-graph](../../index.md) / [shark](../index.md) / [HprofHeapGraph](index.md) / [gcRoots](./gc-roots.md)

# gcRoots

`val gcRoots: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<GcRoot>`

Overrides [HeapGraph.gcRoots](../-heap-graph/gc-roots.md)

All GC roots which type matches types known to this heap graph and which point to non null
references. You can retrieve the object that a GC Root points to by calling [findObjectById](../-heap-graph/find-object-by-id.md)
with [GcRoot.id](#), however you need to first check that [objectExists](../-heap-graph/object-exists.md) returns true because
GC roots can point to objects that don't exist in the heap dump.

