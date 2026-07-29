package shark

import shark.ContentReferences.SKIPPED
import shark.HeapObject.HeapObjectArray
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.ARRAY_ENTRY

/**
 * Expands the non null elements of object arrays.
 *
 * [contentReferences] decides whether the elements of primitive wrapper arrays are followed, see
 * [ContentReferences].
 */
class ObjectArrayReferenceReader(
  private val contentReferences: ContentReferences = SKIPPED
) : ReferenceReader<HeapObjectArray> {
  override fun read(source: HeapObjectArray): Sequence<Reference> {
    if (contentReferences == SKIPPED && source.isSkippablePrimitiveWrapperArray) {
      // primitive wrapper arrays aren't interesting. ShallowSizeCalculator adds the size of the
      // wrapped primitives back onto the array, see ContentReferences.SKIPPED.
      return emptySequence()
    }

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
  internal companion object {
    private val skippablePrimitiveWrapperArrayTypes = setOf(
      Boolean::class,
      Char::class,
      Float::class,
      Double::class,
      Byte::class,
      Short::class,
      Int::class,
      Long::class
    ).map { it.javaObjectType.name + "[]" }

    internal val HeapObjectArray.isSkippablePrimitiveWrapperArray: Boolean
      get() = arrayClassName in skippablePrimitiveWrapperArrayTypes
  }
}
