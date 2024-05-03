package shark

fun ObjectGrowthDetector.Companion.forJvmHeap(
  referenceMatchers: List<ReferenceMatcher> = JdkReferenceMatchers.defaults +
    HeapTraversalOutput.ignoredReferences
): ObjectGrowthDetector {
  return ObjectGrowthDetector(
    gcRootProvider = MatchingGcRootProvider(referenceMatchers),
    referenceReaderFactory = OpenJdkReferenceReaderFactory(referenceMatchers)
  )
}
