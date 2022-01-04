//[leakcanary-android-release](../../../index.md)/[leakcanary](../index.md)/[HeapAnalysisConfig](index.md)/[metadataExtractor](metadata-extractor.md)

# metadataExtractor

[androidJvm]\
val [metadataExtractor](metadata-extractor.md): MetadataExtractor

Extracts metadata from a hprof to be reported in shark.HeapAnalysisSuccess.metadata. Called on a background thread during heap analysis.

Defaults to AndroidMetadataExtractor
