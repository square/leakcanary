//[shark](../../../index.md)/[shark](../index.md)/[AppSingletonInspector](index.md)

# AppSingletonInspector

[jvm]\
class [AppSingletonInspector](index.md)(singletonClasses: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) : [ObjectInspector](../-object-inspector/index.md)

Inspector that automatically marks instances of the provided class names as not leaking because they're app wide singletons.

## Constructors

| | |
|---|---|
| [AppSingletonInspector](-app-singleton-inspector.md) | [jvm]<br>fun [AppSingletonInspector](-app-singleton-inspector.md)(vararg singletonClasses: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) |

## Functions

| Name | Summary |
|---|---|
| [inspect](inspect.md) | [jvm]<br>open override fun [inspect](inspect.md)(reporter: [ObjectReporter](../-object-reporter/index.md)) |
