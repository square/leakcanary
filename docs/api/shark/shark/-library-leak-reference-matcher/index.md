//[shark](../../../index.md)/[shark](../index.md)/[LibraryLeakReferenceMatcher](index.md)

# LibraryLeakReferenceMatcher

[jvm]\
data class [LibraryLeakReferenceMatcher](index.md)(pattern: [ReferencePattern](../-reference-pattern/index.md), description: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), patternApplies: (HeapGraph) -&gt; [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)) : [ReferenceMatcher](../-reference-matcher/index.md)

[LibraryLeakReferenceMatcher](index.md) should be used to match references in library code that are known to create leaks and are beyond your control. The shortest path finder will only go through matching references after it has exhausted references that don't match, prioritizing finding an application leak over a known library leak. Library leaks will be reported as [LibraryLeak](../-library-leak/index.md) instead of [ApplicationLeak](../-application-leak/index.md).

## Constructors

| | |
|---|---|
| [LibraryLeakReferenceMatcher](-library-leak-reference-matcher.md) | [jvm]<br>fun [LibraryLeakReferenceMatcher](-library-leak-reference-matcher.md)(pattern: [ReferencePattern](../-reference-pattern/index.md), description: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) = "", patternApplies: (HeapGraph) -&gt; [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = { true }) |

## Functions

| Name | Summary |
|---|---|
| [toString](to-string.md) | [jvm]<br>open override fun [toString](to-string.md)(): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |

## Properties

| Name | Summary |
|---|---|
| [description](description.md) | [jvm]<br>val [description](description.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>A description that conveys what we know about this library leak. |
| [pattern](pattern.md) | [jvm]<br>open override val [pattern](pattern.md): [ReferencePattern](../-reference-pattern/index.md)<br>The pattern that references will be matched against. |
| [patternApplies](pattern-applies.md) | [jvm]<br>val [patternApplies](pattern-applies.md): (HeapGraph) -&gt; [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether the identified leak may exist in the provided HeapGraph. Defaults to true. If the heap dump comes from a VM that runs a different version of the library that doesn't have the leak, then this should return false. |
