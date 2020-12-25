[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [HeapAnalysisJob](index.md) / [execute](./execute.md)

# execute

`abstract fun execute(): `[`HeapAnalysisJob.Result`](-result/index.md)

Starts the analysis job immediately, and blocks until a result is available.

**Return**
Either [Result.Done](-result/-done/index.md) if the analysis was attempted or [Result.Canceled](-result/-canceled/index.md)

