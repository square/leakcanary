//[leakcanary-android-release](../../../index.md)/[leakcanary](../index.md)/[OncePerPeriodInterceptor](index.md)

# OncePerPeriodInterceptor

[androidJvm]\
class [OncePerPeriodInterceptor](index.md)(application: [Application](https://developer.android.com/reference/kotlin/android/app/Application.html), periodMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [HeapAnalysisInterceptor](../-heap-analysis-interceptor/index.md)

Proceeds once per period (of time) and then cancels all follow up jobs until period has passed.

## Constructors

| | |
|---|---|
| [OncePerPeriodInterceptor](-once-per-period-interceptor.md) | [androidJvm]<br>fun [OncePerPeriodInterceptor](-once-per-period-interceptor.md)(application: [Application](https://developer.android.com/reference/kotlin/android/app/Application.html), periodMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) = TimeUnit.DAYS.toMillis(1)) |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [androidJvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [forget](forget.md) | [androidJvm]<br>fun [forget](forget.md)() |
| [intercept](intercept.md) | [androidJvm]<br>open override fun [intercept](intercept.md)(chain: [HeapAnalysisInterceptor.Chain](../-heap-analysis-interceptor/-chain/index.md)): [HeapAnalysisJob.Result](../-heap-analysis-job/-result/index.md) |
