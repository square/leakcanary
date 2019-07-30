[shark](../../index.md) / [shark](../index.md) / [LibraryLeakReferenceMatcher](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`LibraryLeakReferenceMatcher(pattern: `[`ReferencePattern`](../-reference-pattern/index.md)`, description: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)` = "", patternApplies: (HeapGraph) -> `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = { true })`

[LibraryLeakReferenceMatcher](index.md) should be used to match references in library code that are
known to create leaks and are beyond your control. The shortest path finder will only go
through matching references after it has exhausted references that don't match, prioritizing
finding an application leak over a known library leak. Library leaks will be reported as
[LibraryLeak](../-library-leak/index.md) instead of [ApplicationLeak](../-application-leak/index.md).

