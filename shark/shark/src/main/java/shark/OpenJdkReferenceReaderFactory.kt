package shark

class OpenJdkReferenceReaderFactory(
  private val referenceMatchers: List<ReferenceMatcher>
) : ReferenceReader.Factory<HeapObject> {

  private val virtualizingFactory = VirtualizingMatchingReferenceReaderFactory(
    referenceMatchers = referenceMatchers,
    virtualRefReadersFactory = { graph ->
      listOf(
        JavaLocalReferenceReader(graph, referenceMatchers),
      ) +
        OpenJdkInstanceRefReaders.values().mapNotNull { it.create(graph) } +
        ApacheHarmonyInstanceRefReaders.values().mapNotNull { it.create(graph) }
    }
  )

  override fun createFor(heapGraph: HeapGraph): ReferenceReader<HeapObject> {
    return virtualizingFactory.createFor(heapGraph)
  }
}
