//[leakcanary-android-release](../../../index.md)/[leakcanary](../index.md)/[SaveResourceIdsInterceptor](index.md)

# SaveResourceIdsInterceptor

[androidJvm]\
class [SaveResourceIdsInterceptor](index.md)(resources: [Resources](https://developer.android.com/reference/kotlin/android/content/res/Resources.html)) : [HeapAnalysisInterceptor](../-heap-analysis-interceptor/index.md)

Interceptor that saves the names of R.id.* entries and their associated int values to a static field that can then be read from the heap dump.

## Constructors

| | |
|---|---|
| [SaveResourceIdsInterceptor](-save-resource-ids-interceptor.md) | [androidJvm]<br>fun [SaveResourceIdsInterceptor](-save-resource-ids-interceptor.md)(resources: [Resources](https://developer.android.com/reference/kotlin/android/content/res/Resources.html)) |

## Functions

| Name | Summary |
|---|---|
| [intercept](intercept.md) | [androidJvm]<br>open override fun [intercept](intercept.md)(chain: [HeapAnalysisInterceptor.Chain](../-heap-analysis-interceptor/-chain/index.md)): [HeapAnalysisJob.Result](../-heap-analysis-job/-result/index.md) |
