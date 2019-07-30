[shark-android](../../index.md) / [shark](../index.md) / [AndroidBuildMirror](./index.md)

# AndroidBuildMirror

`class AndroidBuildMirror`

Caches values from the android.os.Build class in the heap dump.
Retrieve a cached instances via [fromHeapGraph](from-heap-graph.md).

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `AndroidBuildMirror(manufacturer: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, sdkInt: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`)`<br>Caches values from the android.os.Build class in the heap dump. Retrieve a cached instances via [fromHeapGraph](from-heap-graph.md). |

### Properties

| Name | Summary |
|---|---|
| [manufacturer](manufacturer.md) | `val manufacturer: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Value of android.os.Build.MANUFACTURER |
| [sdkInt](sdk-int.md) | `val sdkInt: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>Value of android.os.Build.VERSION.SDK_INT |

### Companion Object Functions

| Name | Summary |
|---|---|
| [fromHeapGraph](from-heap-graph.md) | `fun fromHeapGraph(graph: HeapGraph): `[`AndroidBuildMirror`](./index.md) |
