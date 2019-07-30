[leakcanary-android-instrumentation](../../index.md) / [leakcanary](../index.md) / [FailTestOnLeak](./index.md)

# FailTestOnLeak

`@Target([AnnotationTarget.FUNCTION]) annotation class FailTestOnLeak`

An [Annotation](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-annotation/index.html) class to be used in conjunction with [FailAnnotatedTestOnLeakRunListener](../-fail-annotated-test-on-leak-run-listener/index.md)
for detecting memory leaks. When using [FailAnnotatedTestOnLeakRunListener](../-fail-annotated-test-on-leak-run-listener/index.md), the tests
should be annotated with this class in order for the listener to detect memory leaks.

**See Also**

[FailAnnotatedTestOnLeakRunListener](../-fail-annotated-test-on-leak-run-listener/index.md)

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `FailTestOnLeak()`<br>An [Annotation](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-annotation/index.html) class to be used in conjunction with [FailAnnotatedTestOnLeakRunListener](../-fail-annotated-test-on-leak-run-listener/index.md) for detecting memory leaks. When using [FailAnnotatedTestOnLeakRunListener](../-fail-annotated-test-on-leak-run-listener/index.md), the tests should be annotated with this class in order for the listener to detect memory leaks. |
