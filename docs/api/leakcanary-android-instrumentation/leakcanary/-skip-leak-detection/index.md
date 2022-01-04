//[leakcanary-android-instrumentation](../../../index.md)/[leakcanary](../index.md)/[SkipLeakDetection](index.md)

# SkipLeakDetection

[androidJvm]\
@[Target](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.annotation/-target/index.html)(allowedTargets = [[AnnotationTarget.CLASS](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.annotation/-annotation-target/-c-l-a-s-s/index.html), [AnnotationTarget.FUNCTION](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.annotation/-annotation-target/-f-u-n-c-t-i-o-n/index.html)])

annotation class [SkipLeakDetection](index.md)(message: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), assertionTags: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html))

Annotation for skipping leak detection in a UI test that calls [LeakAssertions.assertNoLeaks](../-leak-assertions/assert-no-leaks.md). This annotation is useful to skip a leak detection in a test until the leaks are fixed.

The check is performed by [shouldSkipTest](-companion/should-skip-test.md) which is called by [AndroidDetectLeaksAssert](../-android-detect-leaks-assert/index.md), which requires that the [TestDescriptionHolder](../-test-description-holder/index.md) rule be applied and evaluating when [LeakAssertions.assertNoLeaks](../-leak-assertions/assert-no-leaks.md) is called.

[message](message.md) should contain an explanation of why leak detection is skipped, e.g. a reference to a filed issue.

The optional [assertionTags](assertion-tags.md) allows finer grained filtering based on the tag value passed to [LeakAssertions.assertNoLeaks](../-leak-assertions/assert-no-leaks.md). If [assertionTags](assertion-tags.md) is empty, then the test will skip leak detection entirely. If [assertionTags](assertion-tags.md) is not empty, then the test will skip leak detection for any call to [LeakAssertions.assertNoLeaks](../-leak-assertions/assert-no-leaks.md) with a tag value contained in [assertionTags](assertion-tags.md).

## Constructors

| | |
|---|---|
| [SkipLeakDetection](-skip-leak-detection.md) | [androidJvm]<br>fun [SkipLeakDetection](-skip-leak-detection.md)(message: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), vararg assertionTags: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [androidJvm]<br>object [Companion](-companion/index.md) |

## Properties

| Name | Summary |
|---|---|
| [assertionTags](assertion-tags.md) | [androidJvm]<br>val [assertionTags](assertion-tags.md): [Array](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-array/index.html)&lt;out [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)&gt; |
| [message](message.md) | [androidJvm]<br>val [message](message.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
