//[shark](../../../index.md)/[shark](../index.md)/[ObjectInspector](index.md)

# ObjectInspector

[jvm]\
fun interface [ObjectInspector](index.md)

Provides LeakCanary with insights about objects (classes, instances and arrays) found in the heap. [inspect](inspect.md) will be called for each object that LeakCanary wants to know more about. The implementation can then use the provided [ObjectReporter](../-object-reporter/index.md) to provide insights for that object.

This is a functional interface with which you can create a [ObjectInspector](index.md) from a lambda.

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [inspect](inspect.md) | [jvm]<br>abstract fun [inspect](inspect.md)(reporter: [ObjectReporter](../-object-reporter/index.md)) |

## Inheritors

| Name |
|---|
| [AppSingletonInspector](../-app-singleton-inspector/index.md) |
| [ObjectInspectors](../-object-inspectors/index.md) |
