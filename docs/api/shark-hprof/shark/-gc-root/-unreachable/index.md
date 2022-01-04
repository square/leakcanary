//[shark-hprof](../../../../index.md)/[shark](../../index.md)/[GcRoot](../index.md)/[Unreachable](index.md)

# Unreachable

[jvm]\
class [Unreachable](index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [GcRoot](../index.md)

An object that is unreachable from any other root, but not a root itself.

## Constructors

| | |
|---|---|
| [Unreachable](-unreachable.md) | [jvm]<br>fun [Unreachable](-unreachable.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) |

## Properties

| Name | Summary |
|---|---|
| [id](id.md) | [jvm]<br>open override val [id](id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The object id of the object that this gc root references. |
