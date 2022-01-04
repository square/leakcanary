//[shark](../../../index.md)/[shark](../index.md)/[IgnoredReferenceMatcher](index.md)

# IgnoredReferenceMatcher

[jvm]\
class [IgnoredReferenceMatcher](index.md)(pattern: [ReferencePattern](../-reference-pattern/index.md)) : [ReferenceMatcher](../-reference-matcher/index.md)

[IgnoredReferenceMatcher](index.md) should be used to match references that cannot ever create leaks. The shortest path finder will never go through matching references.

## Constructors

| | |
|---|---|
| [IgnoredReferenceMatcher](-ignored-reference-matcher.md) | [jvm]<br>fun [IgnoredReferenceMatcher](-ignored-reference-matcher.md)(pattern: [ReferencePattern](../-reference-pattern/index.md)) |

## Functions

| Name | Summary |
|---|---|
| [toString](to-string.md) | [jvm]<br>open override fun [toString](to-string.md)(): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |

## Properties

| Name | Summary |
|---|---|
| [pattern](pattern.md) | [jvm]<br>open override val [pattern](pattern.md): [ReferencePattern](../-reference-pattern/index.md)<br>The pattern that references will be matched against. |
