[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [OncePerPeriodInterceptor](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`OncePerPeriodInterceptor(application: Application, periodMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)` = TimeUnit.DAYS.toMillis(1))`

Proceeds once per [period](#) (of time) and then cancels all follow up jobs until [period](#) has
passed.

