//[leakcanary-android-instrumentation](../../../index.md)/[leakcanary](../index.md)/[AndroidDetectLeaksAssert](index.md)

# AndroidDetectLeaksAssert

[androidJvm]\
class [AndroidDetectLeaksAssert](index.md)(heapAnalysisReporter: [HeapAnalysisReporter](../-heap-analysis-reporter/index.md)) : [DetectLeaksAssert](../-detect-leaks-assert/index.md)

Default [DetectLeaksAssert](../-detect-leaks-assert/index.md) implementation. Uses public helpers so you should be able to create our own implementation if needed.

Leak detection can be skipped by annotating tests with [SkipLeakDetection](../-skip-leak-detection/index.md) which requires the [TestDescriptionHolder](../-test-description-holder/index.md) test rule be applied and evaluating when [assertNoLeaks](assert-no-leaks.md) is called.

For improved leak detection, you should consider updating LeakCanary.Config.leakingObjectFinder to FilteringLeakingObjectFinder(AndroidObjectInspectors.appLeakingObjectFilters) when running in instrumentation tests. This changes leak detection from being incremental (based on AppWatcher to also scanning for all objects of known types in the heap).

## Constructors

| | |
|---|---|
| [AndroidDetectLeaksAssert](-android-detect-leaks-assert.md) | [androidJvm]<br>fun [AndroidDetectLeaksAssert](-android-detect-leaks-assert.md)(heapAnalysisReporter: [HeapAnalysisReporter](../-heap-analysis-reporter/index.md) = NoLeakAssertionFailedError.throwOnApplicationLeaks()) |

## Functions

| Name | Summary |
|---|---|
| [assertNoLeaks](assert-no-leaks.md) | [androidJvm]<br>open override fun [assertNoLeaks](assert-no-leaks.md)(tag: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) |
