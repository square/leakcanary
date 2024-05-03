package shark

fun ObjectGrowthDetector.Companion.forAndroidHeap(
  referenceMatchers: List<ReferenceMatcher> = AndroidObjectGrowthReferenceMatchers.defaults
): ObjectGrowthDetector {
  return ObjectGrowthDetector(
    gcRootProvider = MatchingGcRootProvider(referenceMatchers),
    referenceReaderFactory = AndroidReferenceReaderFactory(referenceMatchers)
  )
}
