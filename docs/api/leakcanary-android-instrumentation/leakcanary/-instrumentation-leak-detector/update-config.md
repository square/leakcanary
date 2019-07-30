[leakcanary-android-instrumentation](../../index.md) / [leakcanary](../index.md) / [InstrumentationLeakDetector](index.md) / [updateConfig](./update-config.md)

# updateConfig

`fun updateConfig(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Configures [AppWatcher](#) to watch objects and [LeakCanary](#) to not dump the heap on retained
objects so that instrumentation tests run smoothly, and we can look for leaks at the end of
a test. This is automatically called by [FailTestOnLeakRunListener](../-fail-test-on-leak-run-listener/index.md) when the tests start
running.

