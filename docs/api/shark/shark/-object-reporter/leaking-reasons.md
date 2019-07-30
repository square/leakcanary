[shark](../../index.md) / [shark](../index.md) / [ObjectReporter](index.md) / [leakingReasons](./leaking-reasons.md)

# leakingReasons

`val leakingReasons: `[`MutableSet`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-set/index.html)`<`[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`>`

Reasons for which this object is expected to be unreachable (ie it's leaking).

Only add reasons to this if you're 100% sure this object is leaking, otherwise add reasons to
[likelyLeakingReasons](likely-leaking-reasons.md). The difference is that objects that are "likely leaking" are not
considered to be leaking objects on which LeakCanary should compute the leak trace.

