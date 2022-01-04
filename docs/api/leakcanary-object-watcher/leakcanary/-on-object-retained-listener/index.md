//[leakcanary-object-watcher](../../../index.md)/[leakcanary](../index.md)/[OnObjectRetainedListener](index.md)

# OnObjectRetainedListener

[jvm]\
fun interface [OnObjectRetainedListener](index.md)

Listener used by [ObjectWatcher](../-object-watcher/index.md) to report retained objects.

This is a functional interface with which you can create a [OnObjectRetainedListener](index.md) from a lambda.

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [onObjectRetained](on-object-retained.md) | [jvm]<br>abstract fun [onObjectRetained](on-object-retained.md)()<br>A watched object became retained. |
