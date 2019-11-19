[shark](../../index.md) / [shark](../index.md) / [LibraryLeakReferenceMatcher](./index.md)

# LibraryLeakReferenceMatcher

`data class LibraryLeakReferenceMatcher : `[`ReferenceMatcher`](../-reference-matcher/index.md)

[LibraryLeakReferenceMatcher](./index.md) should be used to match references in library code that are
known to create leaks and are beyond your control. The shortest path finder will only go
through matching references after it has exhausted references that don't match, prioritizing
finding an application leak over a known library leak. Library leaks will be reported as
[LibraryLeak](../-library-leak/index.md) instead of [ApplicationLeak](../-application-leak/index.md).

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `LibraryLeakReferenceMatcher(pattern: `[`ReferencePattern`](../-reference-pattern/index.md)`, description: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)` = "", patternApplies: (HeapGraph) -> `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = { true })`<br>[LibraryLeakReferenceMatcher](./index.md) should be used to match references in library code that are known to create leaks and are beyond your control. The shortest path finder will only go through matching references after it has exhausted references that don't match, prioritizing finding an application leak over a known library leak. Library leaks will be reported as [LibraryLeak](../-library-leak/index.md) instead of [ApplicationLeak](../-application-leak/index.md). |

### Properties

| Name | Summary |
|---|---|
| [description](description.md) | `val description: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>A description that conveys what we know about this library leak. |
| [pattern](pattern.md) | `val pattern: `[`ReferencePattern`](../-reference-pattern/index.md)<br>The pattern that references will be matched against. |
| [patternApplies](pattern-applies.md) | `val patternApplies: (HeapGraph) -> `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether the identified leak may exist in the provided [HeapGraph](#). Defaults to true. If the heap dump comes from a VM that runs a different version of the library that doesn't have the leak, then this should return false. |

### Functions

| Name | Summary |
|---|---|
| [toString](to-string.md) | `fun toString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
