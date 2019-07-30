[shark](../../index.md) / [shark](../index.md) / [ObjectInspectors](./index.md)

# ObjectInspectors

`enum class ObjectInspectors : `[`ObjectInspector`](../-object-inspector/index.md)

A set of default [ObjectInspector](../-object-inspector/index.md)s that knows about common JDK objects.

### Enum Values

| Name | Summary |
|---|---|
| [KEYED_WEAK_REFERENCE](-k-e-y-e-d_-w-e-a-k_-r-e-f-e-r-e-n-c-e/index.md) |  |
| [CLASSLOADER](-c-l-a-s-s-l-o-a-d-e-r/index.md) |  |
| [CLASS](-c-l-a-s-s/index.md) |  |
| [ANONYMOUS_CLASS](-a-n-o-n-y-m-o-u-s_-c-l-a-s-s/index.md) |  |
| [THREAD](-t-h-r-e-a-d/index.md) |  |

### Inherited Functions

| Name | Summary |
|---|---|
| [inspect](../-object-inspector/inspect.md) | `abstract fun inspect(reporter: `[`ObjectReporter`](../-object-reporter/index.md)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |

### Companion Object Properties

| Name | Summary |
|---|---|
| [jdkDefaults](jdk-defaults.md) | `val jdkDefaults: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`ObjectInspector`](../-object-inspector/index.md)`>` |
