//[shark](../../../index.md)/[shark](../index.md)/[ReferencePattern](index.md)

# ReferencePattern

[jvm]\
sealed class [ReferencePattern](index.md) : [Serializable](https://docs.oracle.com/javase/8/docs/api/java/io/Serializable.html)

A pattern that will match references for a given [ReferenceMatcher](../-reference-matcher/index.md).

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |
| [InstanceFieldPattern](-instance-field-pattern/index.md) | [jvm]<br>data class [InstanceFieldPattern](-instance-field-pattern/index.md)(className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), fieldName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) : [ReferencePattern](index.md)<br>Matches instances field references, identified by [className](-instance-field-pattern/class-name.md) and [fieldName](-instance-field-pattern/field-name.md). |
| [JavaLocalPattern](-java-local-pattern/index.md) | [jvm]<br>data class [JavaLocalPattern](-java-local-pattern/index.md)(threadName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) : [ReferencePattern](index.md)<br>Matches local references held in the stack of frames of a given thread, identified by its name. |
| [NativeGlobalVariablePattern](-native-global-variable-pattern/index.md) | [jvm]<br>data class [NativeGlobalVariablePattern](-native-global-variable-pattern/index.md)(className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) : [ReferencePattern](index.md)<br>Matches native global variables (also known as jni global gc roots) that reference Java objects. The class name will match against classes, instances and object arrays with a matching class name. |
| [StaticFieldPattern](-static-field-pattern/index.md) | [jvm]<br>data class [StaticFieldPattern](-static-field-pattern/index.md)(className: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), fieldName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) : [ReferencePattern](index.md)<br>Matches static field references, identified by [className](-static-field-pattern/class-name.md) and [fieldName](-static-field-pattern/field-name.md). |

## Inheritors

| Name |
|---|
| [JavaLocalPattern](-java-local-pattern/index.md) |
| [StaticFieldPattern](-static-field-pattern/index.md) |
| [InstanceFieldPattern](-instance-field-pattern/index.md) |
| [NativeGlobalVariablePattern](-native-global-variable-pattern/index.md) |
