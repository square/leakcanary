[leakcanary-android-process](../../index.md) / [leakcanary](../index.md) / [LeakCanaryProcess](./index.md)

# LeakCanaryProcess

`object LeakCanaryProcess`

Used to determine whether the current process is the LeakCanary analyzer process. By depending
on the `leakcanary-android-process` artifact instead of the `leakcanary-android`, LeakCanary
will automatically run its analysis in a separate process.

As such, you'll need to be careful to do any custom configuration of LeakCanary in both the main
process and the analyzer process.

### Functions

| Name | Summary |
|---|---|
| [isInAnalyzerProcess](is-in-analyzer-process.md) | `fun isInAnalyzerProcess(context: Context): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether the current process is the process running the heap analyzer, which is a different process than the normal app process. |
