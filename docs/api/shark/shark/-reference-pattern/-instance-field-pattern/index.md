//[shark](../../../../index.md)/[shark](../../index.md)/[ReferencePattern](../index.md)/[InstanceFieldPattern](index.md)

# InstanceFieldPattern

[jvm]\
data class [InstanceFieldPattern](index.md)(className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), fieldName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) : [ReferencePattern](../index.md)

Matches instances field references, identified by [className](class-name.md) and [fieldName](field-name.md).

Note: If [fieldName](field-name.md) is declared in a superclass it will still match for subclasses. This is to support overriding of rules for specific cases. If two [ReferenceMatcher](../../-reference-matcher/index.md) match for the same [fieldName](field-name.md) but for different [className](class-name.md) in a class hierarchy, then the closest class in the hierarchy wins.

## Constructors

| | |
|---|---|
| [InstanceFieldPattern](-instance-field-pattern.md) | [jvm]<br>fun [InstanceFieldPattern](-instance-field-pattern.md)(className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), fieldName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [toString](to-string.md) | [jvm]<br>open override fun [toString](to-string.md)(): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |

## Properties

| Name | Summary |
|---|---|
| [className](class-name.md) | [jvm]<br>val [className](class-name.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [fieldName](field-name.md) | [jvm]<br>val [fieldName](field-name.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
