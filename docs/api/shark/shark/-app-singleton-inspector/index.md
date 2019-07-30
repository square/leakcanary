[shark](../../index.md) / [shark](../index.md) / [AppSingletonInspector](./index.md)

# AppSingletonInspector

`class AppSingletonInspector : `[`ObjectInspector`](../-object-inspector/index.md)

Inspector that automatically marks instances of the provided class names as not leaking
because they're app wide singletons.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `AppSingletonInspector(vararg singletonClasses: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`)`<br>Inspector that automatically marks instances of the provided class names as not leaking because they're app wide singletons. |

### Functions

| Name | Summary |
|---|---|
| [inspect](inspect.md) | `fun inspect(reporter: `[`ObjectReporter`](../-object-reporter/index.md)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
