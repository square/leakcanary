[leakcanary-android-instrumentation](../index.md) / [leakcanary](./index.md)

## Package leakcanary

### Types

| Name | Summary |
|---|---|
| [FailAnnotatedTestOnLeakRunListener](-fail-annotated-test-on-leak-run-listener/index.md) | `class FailAnnotatedTestOnLeakRunListener : `[`FailTestOnLeakRunListener`](-fail-test-on-leak-run-listener/index.md)<br>A JUnit [RunListener](#) extending [FailTestOnLeakRunListener](-fail-test-on-leak-run-listener/index.md) to detecting memory leaks in Android instrumentation tests only when the [FailTestOnLeak](-fail-test-on-leak/index.md) annotation is used. |
| [FailTestOnLeakRunListener](-fail-test-on-leak-run-listener/index.md) | `open class FailTestOnLeakRunListener : RunListener`<br>A JUnit [RunListener](#) that uses [InstrumentationLeakDetector](-instrumentation-leak-detector/index.md) to detect memory leaks in Android instrumentation tests. It waits for the end of a test, and if the test succeeds then it will look for retained objects, trigger a heap dump if needed and perform an analysis. |
| [InstrumentationLeakDetector](-instrumentation-leak-detector/index.md) | `class InstrumentationLeakDetector`<br>[InstrumentationLeakDetector](-instrumentation-leak-detector/index.md) can be used to detect memory leaks in instrumentation tests. |

### Annotations

| Name | Summary |
|---|---|
| [FailTestOnLeak](-fail-test-on-leak/index.md) | `annotation class FailTestOnLeak`<br>An [Annotation](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-annotation/index.html) class to be used in conjunction with [FailAnnotatedTestOnLeakRunListener](-fail-annotated-test-on-leak-run-listener/index.md) for detecting memory leaks. When using [FailAnnotatedTestOnLeakRunListener](-fail-annotated-test-on-leak-run-listener/index.md), the tests should be annotated with this class in order for the listener to detect memory leaks. |
