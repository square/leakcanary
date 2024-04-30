package shark

/**
 * Creates [ReferenceReader] instances that will follow references from all [HeapObject]s,
 * applying matching rules provided by [referenceMatchers], and not creating any virtual reference.
 */
class ActualMatchingReferenceReaderFactory(
  private val referenceMatchers: List<ReferenceMatcher>
) : ReferenceReader.Factory<HeapObject> {
  override fun createFor(heapGraph: HeapGraph): ReferenceReader<HeapObject> {
    return DelegatingObjectReferenceReader(
      classReferenceReader = ClassReferenceReader(heapGraph, referenceMatchers),
      instanceReferenceReader = ChainingInstanceReferenceReader(
        virtualRefReaders = listOf(JavaLocalReferenceReader(heapGraph, referenceMatchers)),
        flatteningInstanceReader = null,
        fieldRefReader = FieldInstanceReferenceReader(heapGraph, referenceMatchers)
      ), objectArrayReferenceReader = ObjectArrayReferenceReader()
    )
  }
}
