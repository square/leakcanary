[shark](../../index.md) / [shark](../index.md) / [FilteringLeakingObjectFinder](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`FilteringLeakingObjectFinder(filters: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`FilteringLeakingObjectFinder.LeakingObjectFilter`](-leaking-object-filter/index.md)`>)`

Finds the objects that are leaking by scanning all objects in the heap dump
and delegating the decision to a list of [FilteringLeakingObjectFinder.LeakingObjectFilter](-leaking-object-filter/index.md)

