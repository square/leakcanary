//[leakcanary-android-instrumentation](../../../index.md)/[leakcanary](../index.md)/[DetectLeaksAssert](index.md)

# DetectLeaksAssert

[androidJvm]\
fun interface [DetectLeaksAssert](index.md)

The interface for the implementation that [LeakAssertions.assertNoLeaks](../-leak-assertions/assert-no-leaks.md) delegates to. You can call [DetectLeaksAssert.update](-companion/update.md) to provide your own implementation.

The default implementation is [AndroidDetectLeaksAssert](../-android-detect-leaks-assert/index.md).

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [androidJvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [assertNoLeaks](assert-no-leaks.md) | [androidJvm]<br>abstract fun [assertNoLeaks](assert-no-leaks.md)(tag: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) |

## Inheritors

| Name |
|---|
| [AndroidDetectLeaksAssert](../-android-detect-leaks-assert/index.md) |
