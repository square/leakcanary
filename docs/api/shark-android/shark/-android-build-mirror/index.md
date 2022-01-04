//[shark-android](../../../index.md)/[shark](../index.md)/[AndroidBuildMirror](index.md)

# AndroidBuildMirror

[jvm]\
class [AndroidBuildMirror](index.md)(manufacturer: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), sdkInt: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html))

Caches values from the android.os.Build class in the heap dump. Retrieve a cached instances via [fromHeapGraph](-companion/from-heap-graph.md).

## Constructors

| | |
|---|---|
| [AndroidBuildMirror](-android-build-mirror.md) | [jvm]<br>fun [AndroidBuildMirror](-android-build-mirror.md)(manufacturer: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), sdkInt: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Properties

| Name | Summary |
|---|---|
| [manufacturer](manufacturer.md) | [jvm]<br>val [manufacturer](manufacturer.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Value of android.os.Build.MANUFACTURER |
| [sdkInt](sdk-int.md) | [jvm]<br>val [sdkInt](sdk-int.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>Value of android.os.Build.VERSION.SDK_INT |
