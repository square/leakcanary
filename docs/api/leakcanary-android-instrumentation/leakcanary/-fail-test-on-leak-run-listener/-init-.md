[leakcanary-android-instrumentation](../../index.md) / [leakcanary](../index.md) / [FailTestOnLeakRunListener](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`FailTestOnLeakRunListener()`

A JUnit [RunListener](#) that uses [InstrumentationLeakDetector](../-instrumentation-leak-detector/index.md) to detect memory leaks in Android
instrumentation tests. It waits for the end of a test, and if the test succeeds then it will
look for retained objects, trigger a heap dump if needed and perform an analysis.

[FailTestOnLeakRunListener](index.md) can be subclassed to override [skipLeakDetectionReason](skip-leak-detection-reason.md) and
[onAnalysisPerformed](on-analysis-performed.md)

**See Also**

[InstrumentationLeakDetector](../-instrumentation-leak-detector/index.md)

