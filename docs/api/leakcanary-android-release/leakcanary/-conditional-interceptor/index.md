//[leakcanary-android-release](../../../index.md)/[leakcanary](../index.md)/[ConditionalInterceptor](index.md)

# ConditionalInterceptor

[androidJvm]\
class [ConditionalInterceptor](index.md)(delegate: [HeapAnalysisInterceptor](../-heap-analysis-interceptor/index.md), evaluateCondition: ([HeapAnalysisJob](../-heap-analysis-job/index.md)) -&gt; [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)) : [HeapAnalysisInterceptor](../-heap-analysis-interceptor/index.md)

An interceptor that runs only when evaluateCondition returns true.

## Constructors

| | |
|---|---|
| [ConditionalInterceptor](-conditional-interceptor.md) | [androidJvm]<br>fun [ConditionalInterceptor](-conditional-interceptor.md)(delegate: [HeapAnalysisInterceptor](../-heap-analysis-interceptor/index.md), evaluateCondition: ([HeapAnalysisJob](../-heap-analysis-job/index.md)) -&gt; [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)) |

## Functions

| Name | Summary |
|---|---|
| [intercept](intercept.md) | [androidJvm]<br>open override fun [intercept](intercept.md)(chain: [HeapAnalysisInterceptor.Chain](../-heap-analysis-interceptor/-chain/index.md)): [HeapAnalysisJob.Result](../-heap-analysis-job/-result/index.md) |
