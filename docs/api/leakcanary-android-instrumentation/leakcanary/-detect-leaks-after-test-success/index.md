//[leakcanary-android-instrumentation](../../../index.md)/[leakcanary](../index.md)/[DetectLeaksAfterTestSuccess](index.md)

# DetectLeaksAfterTestSuccess

[androidJvm]\
class [DetectLeaksAfterTestSuccess](index.md)(tag: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) : TestRule

TestRule that invokes [LeakAssertions.assertNoLeaks](../-leak-assertions/assert-no-leaks.md) after the test successfully evaluates. Pay attention to where you set up this rule in the rule chain as you might detect different leaks (e.g. around vs wrapped by the activity rule). It's also possible to use this rule several times in a rule chain.

## Constructors

| | |
|---|---|
| [DetectLeaksAfterTestSuccess](-detect-leaks-after-test-success.md) | [androidJvm]<br>fun [DetectLeaksAfterTestSuccess](-detect-leaks-after-test-success.md)(tag: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) = DetectLeaksAfterTestSuccess::class.java.simpleName) |

## Functions

| Name | Summary |
|---|---|
| [apply](apply.md) | [androidJvm]<br>open override fun [apply](apply.md)(base: Statement, description: Description): Statement |
