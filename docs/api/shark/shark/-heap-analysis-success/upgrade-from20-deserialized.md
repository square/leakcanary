[shark](../../index.md) / [shark](../index.md) / [HeapAnalysisSuccess](index.md) / [upgradeFrom20Deserialized](./upgrade-from20-deserialized.md)

# upgradeFrom20Deserialized

`fun upgradeFrom20Deserialized(fromV20: `[`HeapAnalysisSuccess`](index.md)`): `[`HeapAnalysisSuccess`](index.md)

If [fromV20](upgrade-from20-deserialized.md#shark.HeapAnalysisSuccess.Companion$upgradeFrom20Deserialized(shark.HeapAnalysisSuccess)/fromV20) was serialized in LeakCanary 2.0, you must deserialize it and call this
method to create a usable [HeapAnalysisSuccess](index.md) instance.

