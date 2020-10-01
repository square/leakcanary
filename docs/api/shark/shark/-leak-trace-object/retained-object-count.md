[shark](../../index.md) / [shark](../index.md) / [LeakTraceObject](index.md) / [retainedObjectCount](./retained-object-count.md)

# retainedObjectCount

`val retainedObjectCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`?`

The minimum number of objects which would be unreachable if all references to this object were
released. Not null only if the retained heap size was computed AND [leakingStatus](leaking-status.md) is
equal to [LeakingStatus.UNKNOWN](-leaking-status/-u-n-k-n-o-w-n.md) or [LeakingStatus.LEAKING](-leaking-status/-l-e-a-k-i-n-g.md).

