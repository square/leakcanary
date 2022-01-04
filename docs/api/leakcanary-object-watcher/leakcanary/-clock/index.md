//[leakcanary-object-watcher](../../../index.md)/[leakcanary](../index.md)/[Clock](index.md)

# Clock

[jvm]\
fun interface [Clock](index.md)

An interface to abstract the SystemClock.uptimeMillis() Android API in non Android artifacts.

This is a functional interface with which you can create a [Clock](index.md) from a lambda.

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [uptimeMillis](uptime-millis.md) | [jvm]<br>abstract fun [uptimeMillis](uptime-millis.md)(): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>On Android VMs, this should return android.os.SystemClock.uptimeMillis(). |
