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
    val elementIds = record.elementIds
    // The index in the name of a reference is the index of the entry in the array, so it has to
    // come from iterating the array rather than from the sequence of references, which skips over
    // null entries and over entries that point to an object missing from the heap dump.
    //
    // Iterating with an index also avoids boxing an element id per entry, which is what
    // elementIds.asSequence() does. The index belongs to the iteration rather than to the
    // sequence, so it starts over every time the sequence is read, unlike a captured var, which
    // would keep counting up from where the previous read left off.
    return sequence {
      for (index in elementIds.indices) {
        val elementObjectId = elementIds[index]
        if (elementObjectId != ValueHolder.NULL_REFERENCE && graph.objectExists(elementObjectId)) {
          yield(
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
          )
        }
      }
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
