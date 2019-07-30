[leakcanary-android-instrumentation](../../index.md) / [leakcanary](../index.md) / [FailAnnotatedTestOnLeakRunListener](index.md) / [skipLeakDetectionReason](./skip-leak-detection-reason.md)

# skipLeakDetectionReason

`protected fun skipLeakDetectionReason(description: Description): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`?`

Overrides [FailTestOnLeakRunListener.skipLeakDetectionReason](../-fail-test-on-leak-run-listener/skip-leak-detection-reason.md)

Can be overridden to skip leak detection based on the description provided when a test
is started. Return null to continue leak detection, or a string describing the reason for
skipping otherwise.

