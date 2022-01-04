//[shark](../../../../index.md)/[shark](../../index.md)/[ReferencePattern](../index.md)/[NativeGlobalVariablePattern](index.md)

# NativeGlobalVariablePattern

[jvm]\
data class [NativeGlobalVariablePattern](index.md)(className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) : [ReferencePattern](../index.md)

Matches native global variables (also known as jni global gc roots) that reference Java objects. The class name will match against classes, instances and object arrays with a matching class name.

## Constructors

| | |
|---|---|
| [NativeGlobalVariablePattern](-native-global-variable-pattern.md) | [jvm]<br>fun [NativeGlobalVariablePattern](-native-global-variable-pattern.md)(className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) |

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
