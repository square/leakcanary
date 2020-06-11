[leakcanary-android-instrumentation](../../index.md) / [leakcanary](../index.md) / [FailTestOnLeakRunListener](./index.md)

# FailTestOnLeakRunListener

`open class FailTestOnLeakRunListener : RunListener`

A JUnit [RunListener](#) that uses [InstrumentationLeakDetector](../-instrumentation-leak-detector/index.md) to detect memory leaks in Android
instrumentation tests. It waits for the end of a test, and if the test succeeds then it will
look for retained objects, trigger a heap dump if needed and perform an analysis.

[FailTestOnLeakRunListener](./index.md) can be subclassed to override [skipLeakDetectionReason](skip-leak-detection-reason.md) and
[onAnalysisPerformed](on-analysis-performed.md)

**See Also**

[InstrumentationLeakDetector](../-instrumentation-leak-detector/index.md)

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `FailTestOnLeakRunListener()`<br>A JUnit [RunListener](#) that uses [InstrumentationLeakDetector](../-instrumentation-leak-detector/index.md) to detect memory leaks in Android instrumentation tests. It waits for the end of a test, and if the test succeeds then it will look for retained objects, trigger a heap dump if needed and perform an analysis. |

### Functions

| Name | Summary |
|---|---|
| [failTest](fail-test.md) | `fun failTest(trace: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Reports that the test has failed, with the provided [trace](fail-test.md#leakcanary.FailTestOnLeakRunListener$failTest(kotlin.String)/trace). |
| [onAnalysisPerformed](on-analysis-performed.md) | `open fun onAnalysisPerformed(heapAnalysis: HeapAnalysis): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Called when a heap analysis has been performed and a result is available. |
| [skipLeakDetectionReason](skip-leak-detection-reason.md) | `open fun skipLeakDetectionReason(description: Description): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`?`<br>Can be overridden to skip leak detection based on the description provided when a test is started. Return null to continue leak detection, or a string describing the reason for skipping otherwise. |
| [testAssumptionFailure](test-assumption-failure.md) | `open fun testAssumptionFailure(failure: Failure): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [testFailure](test-failure.md) | `open fun testFailure(failure: Failure): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [testFinished](test-finished.md) | `open fun testFinished(description: Description): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [testIgnored](test-ignored.md) | `open fun testIgnored(description: Description): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [testRunFinished](test-run-finished.md) | `open fun testRunFinished(result: Result): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [testRunStarted](test-run-started.md) | `open fun testRunStarted(description: Description): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [testStarted](test-started.md) | `open fun testStarted(description: Description): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |

### Inheritors

| Name | Summary |
|---|---|
| [FailAnnotatedTestOnLeakRunListener](../-fail-annotated-test-on-leak-run-listener/index.md) | `class FailAnnotatedTestOnLeakRunListener : `[`FailTestOnLeakRunListener`](./index.md)<br>A JUnit [RunListener](#) extending [FailTestOnLeakRunListener](./index.md) to detecting memory leaks in Android instrumentation tests only when the [FailTestOnLeak](../-fail-test-on-leak/index.md) annotation is used. |
