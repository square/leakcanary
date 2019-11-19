[shark](../../index.md) / [shark](../index.md) / [ReferencePattern](./index.md)

# ReferencePattern

`sealed class ReferencePattern : `[`Serializable`](https://docs.oracle.com/javase/6/docs/api/java/io/Serializable.html)

A pattern that will match references for a given [ReferenceMatcher](../-reference-matcher/index.md).

### Types

| Name | Summary |
|---|---|
| [InstanceFieldPattern](-instance-field-pattern/index.md) | `data class InstanceFieldPattern : `[`ReferencePattern`](./index.md)<br>Matches instances field references, identified by [className](-instance-field-pattern/class-name.md) and [fieldName](-instance-field-pattern/field-name.md). |
| [JavaLocalPattern](-java-local-pattern/index.md) | `data class JavaLocalPattern : `[`ReferencePattern`](./index.md)<br>Matches local references held in the stack of frames of a given thread, identified by its name. |
| [NativeGlobalVariablePattern](-native-global-variable-pattern/index.md) | `data class NativeGlobalVariablePattern : `[`ReferencePattern`](./index.md)<br>Matches native global variables (also known as jni global gc roots) that reference Java objects. The class name will match against classes, instances and object arrays with a matching class name. |
| [StaticFieldPattern](-static-field-pattern/index.md) | `data class StaticFieldPattern : `[`ReferencePattern`](./index.md)<br>Matches static field references, identified by [className](-static-field-pattern/class-name.md) and [fieldName](-static-field-pattern/field-name.md). |

### Inheritors

| Name | Summary |
|---|---|
| [InstanceFieldPattern](-instance-field-pattern/index.md) | `data class InstanceFieldPattern : `[`ReferencePattern`](./index.md)<br>Matches instances field references, identified by [className](-instance-field-pattern/class-name.md) and [fieldName](-instance-field-pattern/field-name.md). |
| [JavaLocalPattern](-java-local-pattern/index.md) | `data class JavaLocalPattern : `[`ReferencePattern`](./index.md)<br>Matches local references held in the stack of frames of a given thread, identified by its name. |
| [NativeGlobalVariablePattern](-native-global-variable-pattern/index.md) | `data class NativeGlobalVariablePattern : `[`ReferencePattern`](./index.md)<br>Matches native global variables (also known as jni global gc roots) that reference Java objects. The class name will match against classes, instances and object arrays with a matching class name. |
| [StaticFieldPattern](-static-field-pattern/index.md) | `data class StaticFieldPattern : `[`ReferencePattern`](./index.md)<br>Matches static field references, identified by [className](-static-field-pattern/class-name.md) and [fieldName](-static-field-pattern/field-name.md). |
