package shark

import shark.HeapObject.HeapObjectArray
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.ARRAY_ENTRY

class ObjectArrayReferenceReader : ReferenceReader<HeapObjectArray> {
  override fun read(source: HeapObjectArray): Sequence<Reference> {
    if (source.isSkippablePrimitiveWrapperArray) {
      // primitive wrapper arrays aren't interesting.
      // That also means the wrapped size isn't added to the dominator tree, so we need to
      // add that back when computing shallow size in ShallowSizeCalculator.
      // Another side effect is that if the wrapped primitive is referenced elsewhere, we might
      // double count its size.
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
