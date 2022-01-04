//[shark-graph](../../../index.md)/[shark](../index.md)/[HeapGraph](index.md)/[gcRoots](gc-roots.md)

# gcRoots

[jvm]\
abstract val [gcRoots](gc-roots.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;GcRoot&gt;

All GC roots which type matches types known to this heap graph and which point to non null references. You can retrieve the object that a GC Root points to by calling [findObjectById](find-object-by-id.md) with GcRoot.id, however you need to first check that [objectExists](object-exists.md) returns true because GC roots can point to objects that don't exist in the heap dump.
