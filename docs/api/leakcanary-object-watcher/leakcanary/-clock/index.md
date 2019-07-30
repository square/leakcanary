[leakcanary-object-watcher](../../index.md) / [leakcanary](../index.md) / [Clock](./index.md)

# Clock

`interface Clock`

An interface to abstract the SystemClock.uptimeMillis() Android API in non Android artifacts.

### Functions

| Name | Summary |
|---|---|
| [uptimeMillis](uptime-millis.md) | `abstract fun uptimeMillis(): `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>On Android VMs, this should return android.os.SystemClock.uptimeMillis(). |
