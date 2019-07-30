[shark](../../index.md) / [shark](../index.md) / [ObjectReporter](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`ObjectReporter(heapObject: HeapObject)`

Enables [ObjectInspector](../-object-inspector/index.md) implementations to provide insights on [heapObject](heap-object.md), which is
an object (class, instance or array) found in the heap.

A given [ObjectReporter](index.md) only maps to one object in the heap, but is shared to many
[ObjectInspector](../-object-inspector/index.md) implementations and accumulates insights.

