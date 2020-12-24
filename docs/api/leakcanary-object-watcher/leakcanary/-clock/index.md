[leakcanary-object-watcher](../../index.md) / [leakcanary](../index.md) / [Clock](./index.md)

# Clock

`interface Clock`

### Functions

| Name | Summary |
|---|---|
| [uptimeMillis](uptime-millis.md) | `abstract fun uptimeMillis(): `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>On Android VMs, this should return android.os.SystemClock.uptimeMillis(). |

### Companion Object Functions

| Name | Summary |
|---|---|
| [invoke](invoke.md) | `operator fun invoke(block: () -> `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`Clock`](./index.md)<br>Utility function to create a [Clock](./index.md) from the passed in [block](invoke.md#leakcanary.Clock.Companion$invoke(kotlin.Function0((kotlin.Long)))/block) lambda instead of using the anonymous `object : Clock` syntax. |
