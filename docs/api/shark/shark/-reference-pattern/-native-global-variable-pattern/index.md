[shark](../../../index.md) / [shark](../../index.md) / [ReferencePattern](../index.md) / [NativeGlobalVariablePattern](./index.md)

# NativeGlobalVariablePattern

`data class NativeGlobalVariablePattern : `[`ReferencePattern`](../index.md)

Matches native global variables (also known as jni global gc roots) that reference
Java objects. The class name will match against classes, instances and object arrays with
a matching class name.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `NativeGlobalVariablePattern(className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`)`<br>Matches native global variables (also known as jni global gc roots) that reference Java objects. The class name will match against classes, instances and object arrays with a matching class name. |

### Properties

| Name | Summary |
|---|---|
| [className](class-name.md) | `val className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |

### Functions

| Name | Summary |
|---|---|
| [toString](to-string.md) | `fun toString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
