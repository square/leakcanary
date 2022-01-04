//[shark-hprof](../../../../index.md)/[shark](../../index.md)/[GcRoot](../index.md)/[MonitorUsed](index.md)

# MonitorUsed

[jvm]\
class [MonitorUsed](index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [GcRoot](../index.md)

Everything that called the wait() or notify() methods, or that is synchronized.

## Constructors

| | |
|---|---|
| [MonitorUsed](-monitor-used.md) | [jvm]<br>fun [MonitorUsed](-monitor-used.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) |

## Properties

| Name | Summary |
|---|---|
| [id](id.md) | [jvm]<br>open override val [id](id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The object id of the object that this gc root references. |
