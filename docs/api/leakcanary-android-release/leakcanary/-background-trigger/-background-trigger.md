//[leakcanary-android-release](../../../index.md)/[leakcanary](../index.md)/[BackgroundTrigger](index.md)/[BackgroundTrigger](-background-trigger.md)

# BackgroundTrigger

[androidJvm]\
fun [BackgroundTrigger](-background-trigger.md)(application: [Application](https://developer.android.com/reference/kotlin/android/app/Application.html), analysisClient: [HeapAnalysisClient](../-heap-analysis-client/index.md), analysisExecutor: [Executor](https://developer.android.com/reference/kotlin/java/util/concurrent/Executor.html), processInfo: [ProcessInfo](../-process-info/index.md) = ProcessInfo.Real, analysisCallback: ([HeapAnalysisJob.Result](../-heap-analysis-job/-result/index.md)) -&gt; [Unit](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) = { result -&gt;
    SharkLog.d { "$result" }
  })
