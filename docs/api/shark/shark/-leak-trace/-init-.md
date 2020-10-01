[shark](../../index.md) / [shark](../index.md) / [LeakTrace](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`LeakTrace(gcRootType: `[`LeakTrace.GcRootType`](-gc-root-type/index.md)`, referencePath: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`LeakTraceReference`](../-leak-trace-reference/index.md)`>, leakingObject: `[`LeakTraceObject`](../-leak-trace-object/index.md)`)`

The best strong reference path from a GC root to the leaking object. "Best" here means the
shortest prioritized path. A large number of distinct paths can generally be found leading
to a leaking object. Shark prioritizes paths that don't go through known
[LibraryLeakReferenceMatcher](../-library-leak-reference-matcher/index.md) (because those are known to create leaks so it's more interesting
to find other paths causing leaks), then it prioritize paths that don't go through java local
gc roots (because those are harder to reason about). Taking those priorities into account,
finding the shortest path means there are less [LeakTraceReference](../-leak-trace-reference/index.md) that can be suspected to
cause the leak.

