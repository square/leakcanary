

### All Types

| Name | Summary |
|---|---|
| [leakcanary.FailAnnotatedTestOnLeakRunListener](../leakcanary/-fail-annotated-test-on-leak-run-listener/index.md) | A JUnit [RunListener](#) extending [FailTestOnLeakRunListener](../leakcanary/-fail-test-on-leak-run-listener/index.md) to detecting memory leaks in Android instrumentation tests only when the [FailTestOnLeak](../leakcanary/-fail-test-on-leak/index.md) annotation is used. |
| [leakcanary.FailTestOnLeak](../leakcanary/-fail-test-on-leak/index.md) | An [Annotation](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-annotation/index.html) class to be used in conjunction with [FailAnnotatedTestOnLeakRunListener](../leakcanary/-fail-annotated-test-on-leak-run-listener/index.md) for detecting memory leaks. When using [FailAnnotatedTestOnLeakRunListener](../leakcanary/-fail-annotated-test-on-leak-run-listener/index.md), the tests should be annotated with this class in order for the listener to detect memory leaks. |
| [leakcanary.FailTestOnLeakRunListener](../leakcanary/-fail-test-on-leak-run-listener/index.md) | A JUnit [RunListener](#) that uses [InstrumentationLeakDetector](../leakcanary/-instrumentation-leak-detector/index.md) to detect memory leaks in Android instrumentation tests. It waits for the end of a test, and if the test succeeds then it will look for retained objects, trigger a heap dump if needed and perform an analysis. |
| [leakcanary.InstrumentationLeakDetector](../leakcanary/-instrumentation-leak-detector/index.md) | [InstrumentationLeakDetector](../leakcanary/-instrumentation-leak-detector/index.md) can be used to detect memory leaks in instrumentation tests. |
