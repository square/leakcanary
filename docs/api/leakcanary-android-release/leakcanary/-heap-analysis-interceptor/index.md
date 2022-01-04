//[leakcanary-android-release](../../../index.md)/[leakcanary](../index.md)/[HeapAnalysisInterceptor](index.md)

# HeapAnalysisInterceptor

[androidJvm]\
fun interface [HeapAnalysisInterceptor](index.md)

## Types

| Name | Summary |
|---|---|
| [Chain](-chain/index.md) | [androidJvm]<br>interface [Chain](-chain/index.md) |

## Functions

| Name | Summary |
|---|---|
| [intercept](intercept.md) | [androidJvm]<br>abstract fun [intercept](intercept.md)(chain: [HeapAnalysisInterceptor.Chain](-chain/index.md)): [HeapAnalysisJob.Result](../-heap-analysis-job/-result/index.md) |

## Inheritors

| Name |
|---|
| [ConditionalInterceptor](../-conditional-interceptor/index.md) |
| [GoodAndroidVersionInterceptor](../-good-android-version-interceptor/index.md) |
| [MinimumDiskSpaceInterceptor](../-minimum-disk-space-interceptor/index.md) |
| [MinimumElapsedSinceStartInterceptor](../-minimum-elapsed-since-start-interceptor/index.md) |
| [MinimumMemoryInterceptor](../-minimum-memory-interceptor/index.md) |
| [OncePerPeriodInterceptor](../-once-per-period-interceptor/index.md) |
| [SaveResourceIdsInterceptor](../-save-resource-ids-interceptor/index.md) |
