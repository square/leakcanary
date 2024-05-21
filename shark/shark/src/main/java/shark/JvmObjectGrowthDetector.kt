package shark

fun ObjectGrowthDetector.Companion.forJvmHeap(
  referenceMatchers: List<ReferenceMatcher> = JvmObjectGrowthReferenceMatchers.defaults
): ObjectGrowthDetector {
  return ObjectGrowthDetector(
    gcRootProvider = MatchingGcRootProvider(referenceMatchers),
    referenceReaderFactory = OpenJdkReferenceReaderFactory(referenceMatchers)
  )
}
