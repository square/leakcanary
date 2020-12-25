[leakcanary-object-watcher](../../index.md) / [leakcanary](../index.md) / [OnObjectRetainedListener](./index.md)

# OnObjectRetainedListener

`interface OnObjectRetainedListener`

### Functions

| Name | Summary |
|---|---|
| [onObjectRetained](on-object-retained.md) | `abstract fun onObjectRetained(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>A watched object became retained. |

### Companion Object Functions

| Name | Summary |
|---|---|
| [invoke](invoke.md) | `operator fun invoke(block: () -> `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)`): `[`OnObjectRetainedListener`](./index.md)<br>Utility function to create a [OnObjectRetainedListener](./index.md) from the passed in [block](invoke.md#leakcanary.OnObjectRetainedListener.Companion$invoke(kotlin.Function0((kotlin.Unit)))/block) lambda instead of using the anonymous `object : OnObjectRetainedListener` syntax. |
