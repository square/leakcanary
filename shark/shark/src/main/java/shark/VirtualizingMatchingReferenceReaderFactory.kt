package shark

import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader

// TODO Move to shark-android once HeapAnalyzer is removed.
// TODO This should be configurable to provide your own ref readers. Maybe just
/**
 * Creates [ReferenceReader] instances that will follow references from all [HeapObject]s,
 * applying matching rules provided by [referenceMatchers], creating additional virtual instance
 * reference based on the list of [VirtualInstanceReferenceReader] created by [virtualRefReadersFactory].
 */
class VirtualizingMatchingReferenceReaderFactory(
  private val referenceMatchers: List<ReferenceMatcher>,
  private val virtualRefReadersFactory: VirtualInstanceReferenceReader.ChainFactory
  ) : ReferenceReader.Factory<HeapObject> {
  override fun createFor(heapGraph: HeapGraph): ReferenceReader<HeapObject> {
    return DelegatingObjectReferenceReader(
      classReferenceReader = ClassReferenceReader(heapGraph, referenceMatchers),
      instanceReferenceReader = ChainingInstanceReferenceReader(
        virtualRefReaders = virtualRefReadersFactory.createFor(heapGraph),
        fieldRefReader = FieldInstanceReferenceReader(heapGraph, referenceMatchers)
      ),
      objectArrayReferenceReader = ObjectArrayReferenceReader()
    )
  }
}
