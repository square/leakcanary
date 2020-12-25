[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [HeapAnalysisConfig](index.md) / [metadataExtractor](./metadata-extractor.md)

# metadataExtractor

`val metadataExtractor: MetadataExtractor`

Extracts metadata from a hprof to be reported in [shark.HeapAnalysisSuccess.metadata](#).
Called on a background thread during heap analysis.

Defaults to [AndroidMetadataExtractor](#)

