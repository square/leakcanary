package shark

fun ObjectGrowthDetector.Companion.androidDetector(
  referenceMatchers: List<ReferenceMatcher> = AndroidHeapGrowthIgnoredReferences.defaults
): ObjectGrowthDetector {
  return ObjectGrowthDetector(
    gcRootProvider = MatchingGcRootProvider(referenceMatchers),
    referenceReaderFactory = AndroidReferenceReaderFactory(referenceMatchers)
  )
}
