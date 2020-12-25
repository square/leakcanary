[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [ProcessInfo](./index.md)

# ProcessInfo

`interface ProcessInfo`

### Types

| Name | Summary |
|---|---|
| [AvailableRam](-available-ram/index.md) | `sealed class AvailableRam` |
| [Real](-real/index.md) | `object Real : `[`ProcessInfo`](./index.md) |

### Properties

| Name | Summary |
|---|---|
| [elapsedMillisSinceStart](elapsed-millis-since-start.md) | `abstract val elapsedMillisSinceStart: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [isImportanceBackground](is-importance-background.md) | `abstract val isImportanceBackground: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |

### Functions

| Name | Summary |
|---|---|
| [availableDiskSpaceBytes](available-disk-space-bytes.md) | `abstract fun availableDiskSpaceBytes(path: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`): `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [availableRam](available-ram.md) | `abstract fun availableRam(context: Context): `[`ProcessInfo.AvailableRam`](-available-ram/index.md) |

### Inheritors

| Name | Summary |
|---|---|
| [Real](-real/index.md) | `object Real : `[`ProcessInfo`](./index.md) |
