package shark

object RepeatedObjectGrowthDetectorJvmFactory {
  fun create(
    referenceMatchers: List<ReferenceMatcher> = JdkReferenceMatchers.defaults +
      HeapTraversal.ignoredReferences
  ): RepeatedObjectGrowthDetector {
    return HeapGraphSequenceObjectGrowthDetector(
      HeapGraphObjectGrowthDetector(
        gcRootProvider = MatchingGcRootProvider(referenceMatchers),
        referenceReaderFactory = OpenJdkReferenceReaderFactory(referenceMatchers)
      )
    )
  }
}
