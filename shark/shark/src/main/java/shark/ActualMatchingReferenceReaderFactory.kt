package shark

import shark.ContentReferences.SKIPPED

/**
 * Creates [ReferenceReader] instances that will follow references from all [HeapObject]s,
 * applying matching rules provided by [referenceMatchers], and not creating any virtual reference.
 *
 * [contentReferences] is passed on to the readers that can skip content references, see
 * [ContentReferences]. An [ObjectSizeCalculator] used on the same traversal has to be given the
 * same value.
 */
class ActualMatchingReferenceReaderFactory(
  private val referenceMatchers: List<ReferenceMatcher>,
  private val contentReferences: ContentReferences = SKIPPED
) : ReferenceReader.Factory<HeapObject> {
  override fun createFor(heapGraph: HeapGraph): ReferenceReader<HeapObject> {
    return DelegatingObjectReferenceReader(
      classReferenceReader = ClassReferenceReader(heapGraph, referenceMatchers),
      instanceReferenceReader = ChainingInstanceReferenceReader(
        virtualRefReaders = listOf(JavaLocalReferenceReader(heapGraph, referenceMatchers)),
        flatteningInstanceReader = null,
        fieldRefReader = FieldInstanceReferenceReader(
          heapGraph,
          referenceMatchers,
          contentReferences
        )
      ), objectArrayReferenceReader = ObjectArrayReferenceReader(contentReferences)
    )
  }
}
