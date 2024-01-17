package shark

object RepeatedObjectGrowthDetectorAndroidFactory {
  fun create(referenceMatchers: List<ReferenceMatcher> = AndroidHeapGrowthIgnoredReferences.defaults): RepeatedObjectGrowthDetector {
    return HeapGraphSequenceObjectGrowthDetector(
      HeapGraphObjectGrowthDetector(
        gcRootProvider = MatchingGcRootProvider(referenceMatchers),
        referenceReaderFactory =  AndroidReferenceReaderFactory(referenceMatchers)
      )
    )
  }
}
