package shark

// TODO Move to shark-android once HeapAnalyzer is removed.
// TODO Not sure if this class should exist of be some sort of configuration instead.
/**
 * Creates [ReferenceReader] instances that will follow references from all [HeapObject]s,
 * applying matching rules provided by [referenceMatchers], creating additional virtual instance
 * reference based on known Android classes.
 */
class AndroidReferenceReaderFactory(
  private val referenceMatchers: List<ReferenceMatcher>
) : ReferenceReader.Factory<HeapObject> {

  private val virtualizingFactory = VirtualizingMatchingReferenceReaderFactory(
    referenceMatchers = referenceMatchers,
    virtualRefReadersFactory = { graph ->
      listOf(
        JavaLocalReferenceReader(graph, referenceMatchers),
      ) +
        AndroidReferenceReaders.values().mapNotNull { it.create(graph) } +
        OpenJdkInstanceRefReaders.values().mapNotNull { it.create(graph) } +
        ApacheHarmonyInstanceRefReaders.values().mapNotNull { it.create(graph) }
    }
  )

  override fun createFor(heapGraph: HeapGraph): ReferenceReader<HeapObject> {
    return virtualizingFactory.createFor(heapGraph)
  }
}
