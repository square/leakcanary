[shark](../../../index.md) / [shark](../../index.md) / [ReferencePattern](../index.md) / [InstanceFieldPattern](./index.md)

# InstanceFieldPattern

`data class InstanceFieldPattern : `[`ReferencePattern`](../index.md)

Matches instances field references, identified by [className](class-name.md) and [fieldName](field-name.md).

Note: If [fieldName](field-name.md) is declared in a superclass it will still match for subclasses.
This is to support overriding of rules for specific cases. If two [ReferenceMatcher](../../-reference-matcher/index.md) match for
the same [fieldName](field-name.md) but for different [className](class-name.md) in a class hierarchy, then the closest
class in the hierarchy wins.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `InstanceFieldPattern(className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, fieldName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`)`<br>Matches instances field references, identified by [className](class-name.md) and [fieldName](field-name.md). |

### Properties

| Name | Summary |
|---|---|
| [className](class-name.md) | `val className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [fieldName](field-name.md) | `val fieldName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |

### Functions

| Name | Summary |
|---|---|
| [toString](to-string.md) | `fun toString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
