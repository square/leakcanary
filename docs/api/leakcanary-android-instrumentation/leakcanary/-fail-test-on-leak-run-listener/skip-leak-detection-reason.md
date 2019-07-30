[leakcanary-android-instrumentation](../../index.md) / [leakcanary](../index.md) / [FailTestOnLeakRunListener](index.md) / [skipLeakDetectionReason](./skip-leak-detection-reason.md)

# skipLeakDetectionReason

`protected open fun skipLeakDetectionReason(description: Description): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`?`

Can be overridden to skip leak detection based on the description provided when a test
is started. Return null to continue leak detection, or a string describing the reason for
skipping otherwise.

