[shark](../../index.md) / [shark](../index.md) / [LeakTrace](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`LeakTrace(elements: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`LeakTraceElement`](../-leak-trace-element/index.md)`>)`

A chain of references that constitute the shortest strong reference path from a GC root to the
leaking object. Fixing the leak usually means breaking one of the references in that chain.

