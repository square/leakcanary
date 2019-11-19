[leakcanary-android-core](../../../index.md) / [leakcanary](../../index.md) / [LeakCanary](../index.md) / [Config](index.md) / [metatadaExtractor](./metatada-extractor.md)

# metatadaExtractor

`val metatadaExtractor: MetadataExtractor`

Extracts metadata from a hprof to be reported in [HeapAnalysisSuccess.metadata](#).
Called on a background thread during heap analysis.

Defaults to [AndroidMetadataExtractor](#)

