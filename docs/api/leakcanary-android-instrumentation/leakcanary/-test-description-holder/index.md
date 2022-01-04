//[leakcanary-android-instrumentation](../../../index.md)/[leakcanary](../index.md)/[TestDescriptionHolder](index.md)

# TestDescriptionHolder

[androidJvm]\
object [TestDescriptionHolder](index.md) : TestRule

A TestRule that holds onto the test Description in a thread local while evaluating, making it possible to retrieve that test Description from the test thread via [testDescription](test-description.md).

## Functions

| Name | Summary |
|---|---|
| [apply](apply.md) | [androidJvm]<br>open override fun [apply](apply.md)(base: Statement, description: Description): Statement |
| [isEvaluating](is-evaluating.md) | [androidJvm]<br>fun [isEvaluating](is-evaluating.md)(): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |

## Properties

| Name | Summary |
|---|---|
| [testDescription](test-description.md) | [androidJvm]<br>val [testDescription](test-description.md): Description |
