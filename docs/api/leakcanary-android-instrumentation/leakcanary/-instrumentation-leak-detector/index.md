[leakcanary-android-instrumentation](../../index.md) / [leakcanary](../index.md) / [InstrumentationLeakDetector](./index.md)

# InstrumentationLeakDetector

`class InstrumentationLeakDetector`

[InstrumentationLeakDetector](./index.md) can be used to detect memory leaks in instrumentation tests.

To use it, you need to add an instrumentation test listener (e.g. [FailTestOnLeakRunListener](../-fail-test-on-leak-run-listener/index.md))
that will invoke [detectLeaks](detect-leaks.md).

### Add an instrumentation test listener

LeakCanary provides [FailTestOnLeakRunListener](../-fail-test-on-leak-run-listener/index.md), but you can also implement
your own [RunListener](#) and call [detectLeaks](detect-leaks.md) directly if you need a more custom
behavior (for instance running it only once per test suite).

All you need to do is add the following to the defaultConfig of your build.gradle:

`testInstrumentationRunnerArgument "listener", "leakcanary.FailTestOnLeakRunListener"`

Then you can run your instrumentation tests via Gradle as usually, and they will fail when
a memory leak is detected:

`./gradlew leakcanary-sample:connectedCheck`

If instead you want to run UI tests via adb, add a *listener* execution argument to
your command line for running the UI tests:
`-e listener leakcanary.FailTestOnLeakRunListener`. The full command line
should look something like this:

```
adb shell am instrument \\
-w com.android.foo/android.support.test.runner.AndroidJUnitRunner \\
-e listener leakcanary.FailTestOnLeakRunListener
```

### Rationale

Instead of using the [InstrumentationLeakDetector](./index.md), one could simply enable LeakCanary in
instrumentation tests.

This approach would have two disadvantages:

* Heap dumps freeze the VM, and the leak analysis is IO and CPU heavy. This can slow down
the test and introduce flakiness
* The leak analysis is asynchronous by default. This means the tests could finish and the
process dies before the analysis is finished.

The approach taken here is to collect all objects to watch as you run the test, but not
do any heap dump during the test. Then, at the end, if any of the watched objects is still in
memory we dump the heap and perform a blocking analysis. There is only one heap dump performed,
no matter the number of objects retained.

### Types

| Name | Summary |
|---|---|
| [Result](-result/index.md) | `sealed class Result`<br>The result of calling [detectLeaks](detect-leaks.md), which is either [NoAnalysis](-result/-no-analysis.md) or [AnalysisPerformed](-result/-analysis-performed/index.md). |

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `InstrumentationLeakDetector()`<br>[InstrumentationLeakDetector](./index.md) can be used to detect memory leaks in instrumentation tests. |

### Functions

| Name | Summary |
|---|---|
| [detectLeaks](detect-leaks.md) | `fun detectLeaks(): `[`InstrumentationLeakDetector.Result`](-result/index.md)<br>Looks for retained objects, triggers a heap dump if needed and performs an analysis. |

### Companion Object Functions

| Name | Summary |
|---|---|
| [updateConfig](update-config.md) | `fun ~~updateConfig~~(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
