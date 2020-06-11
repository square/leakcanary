[leakcanary-android-instrumentation](../../index.md) / [leakcanary](../index.md) / [FailAnnotatedTestOnLeakRunListener](./index.md)

# FailAnnotatedTestOnLeakRunListener

`class FailAnnotatedTestOnLeakRunListener : `[`FailTestOnLeakRunListener`](../-fail-test-on-leak-run-listener/index.md)

A JUnit [RunListener](#) extending [FailTestOnLeakRunListener](../-fail-test-on-leak-run-listener/index.md) to detecting memory
leaks in Android instrumentation tests only when the [FailTestOnLeak](../-fail-test-on-leak/index.md) annotation
is used.

**See Also**

[FailTestOnLeak](../-fail-test-on-leak/index.md)

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `FailAnnotatedTestOnLeakRunListener()`<br>A JUnit [RunListener](#) extending [FailTestOnLeakRunListener](../-fail-test-on-leak-run-listener/index.md) to detecting memory leaks in Android instrumentation tests only when the [FailTestOnLeak](../-fail-test-on-leak/index.md) annotation is used. |

### Functions

| Name | Summary |
|---|---|
| [skipLeakDetectionReason](skip-leak-detection-reason.md) | `fun skipLeakDetectionReason(description: Description): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`?`<br>Can be overridden to skip leak detection based on the description provided when a test is started. Return null to continue leak detection, or a string describing the reason for skipping otherwise. |

### Inherited Functions

| Name | Summary |
|---|---|
| [failTest](../-fail-test-on-leak-run-listener/fail-test.md) | `fun failTest(trace: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Reports that the test has failed, with the provided [trace](../-fail-test-on-leak-run-listener/fail-test.md#leakcanary.FailTestOnLeakRunListener$failTest(kotlin.String)/trace). |
| [onAnalysisPerformed](../-fail-test-on-leak-run-listener/on-analysis-performed.md) | `open fun onAnalysisPerformed(heapAnalysis: HeapAnalysis): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Called when a heap analysis has been performed and a result is available. |
| [testAssumptionFailure](../-fail-test-on-leak-run-listener/test-assumption-failure.md) | `open fun testAssumptionFailure(failure: Failure): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [testFailure](../-fail-test-on-leak-run-listener/test-failure.md) | `open fun testFailure(failure: Failure): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [testFinished](../-fail-test-on-leak-run-listener/test-finished.md) | `open fun testFinished(description: Description): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [testIgnored](../-fail-test-on-leak-run-listener/test-ignored.md) | `open fun testIgnored(description: Description): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [testRunFinished](../-fail-test-on-leak-run-listener/test-run-finished.md) | `open fun testRunFinished(result: Result): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [testRunStarted](../-fail-test-on-leak-run-listener/test-run-started.md) | `open fun testRunStarted(description: Description): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [testStarted](../-fail-test-on-leak-run-listener/test-started.md) | `open fun testStarted(description: Description): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
