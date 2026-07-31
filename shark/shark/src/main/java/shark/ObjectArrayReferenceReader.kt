package shark

import shark.HeapObject.HeapObjectArray
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.ARRAY_ENTRY

class ObjectArrayReferenceReader : ReferenceReader<HeapObjectArray> {
  override fun read(source: HeapObjectArray): Sequence<Reference> {
    val graph = source.graph
    val record = source.readRecord()
    val arrayClassId = source.arrayClassId
    return record.elementIds.asSequence().filter { objectId ->
      objectId != ValueHolder.NULL_REFERENCE && graph.objectExists(objectId)
    }.mapIndexed { index, elementObjectId ->
      Reference(
        valueObjectId = elementObjectId,
        isLowPriority = false,
        lazyDetailsResolver = {
          LazyDetails(
            name = index.toString(),
            locationClassObjectId = arrayClassId,
            locationType = ARRAY_ENTRY,
            isVirtual = false,
            matchedLibraryLeak = null
          )
        }
      )
    }
  }
}
