[shark](../../index.md) / [shark](../index.md) / [ReferenceMatcher](./index.md)

# ReferenceMatcher

`sealed class ReferenceMatcher`

Used to pattern match known patterns of references in the heap, either to ignore them
([IgnoredReferenceMatcher](../-ignored-reference-matcher/index.md)) or to mark them as library leaks ([LibraryLeakReferenceMatcher](../-library-leak-reference-matcher/index.md)).

### Properties

| Name | Summary |
|---|---|
| [pattern](pattern.md) | `abstract val pattern: `[`ReferencePattern`](../-reference-pattern/index.md)<br>The pattern that references will be matched against. |

### Inheritors

| Name | Summary |
|---|---|
| [IgnoredReferenceMatcher](../-ignored-reference-matcher/index.md) | `class IgnoredReferenceMatcher : `[`ReferenceMatcher`](./index.md)<br>[IgnoredReferenceMatcher](../-ignored-reference-matcher/index.md) should be used to match references that cannot ever create leaks. The shortest path finder will never go through matching references. |
| [LibraryLeakReferenceMatcher](../-library-leak-reference-matcher/index.md) | `data class LibraryLeakReferenceMatcher : `[`ReferenceMatcher`](./index.md)<br>[LibraryLeakReferenceMatcher](../-library-leak-reference-matcher/index.md) should be used to match references in library code that are known to create leaks and are beyond your control. The shortest path finder will only go through matching references after it has exhausted references that don't match, prioritizing finding an application leak over a known library leak. Library leaks will be reported as [LibraryLeak](../-library-leak/index.md) instead of [ApplicationLeak](../-application-leak/index.md). |
