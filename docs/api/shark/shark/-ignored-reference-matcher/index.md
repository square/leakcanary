[shark](../../index.md) / [shark](../index.md) / [IgnoredReferenceMatcher](./index.md)

# IgnoredReferenceMatcher

`class IgnoredReferenceMatcher : `[`ReferenceMatcher`](../-reference-matcher/index.md)

[IgnoredReferenceMatcher](./index.md) should be used to match references that cannot ever create leaks. The
shortest path finder will never go through matching references.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `IgnoredReferenceMatcher(pattern: `[`ReferencePattern`](../-reference-pattern/index.md)`)`<br>[IgnoredReferenceMatcher](./index.md) should be used to match references that cannot ever create leaks. The shortest path finder will never go through matching references. |

### Properties

| Name | Summary |
|---|---|
| [pattern](pattern.md) | `val pattern: `[`ReferencePattern`](../-reference-pattern/index.md)<br>The pattern that references will be matched against. |

### Functions

| Name | Summary |
|---|---|
| [toString](to-string.md) | `fun toString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
