package shark.internal

import shark.HeapGraph
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.ObjectArrayReferenceReader.Companion.isSkippablePrimitiveWrapperArray
import shark.ValueHolder
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.ByteHolder
import shark.ValueHolder.CharHolder
import shark.ValueHolder.DoubleHolder
import shark.ValueHolder.FloatHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder
import shark.ValueHolder.ShortHolder

/**
 * Provides approximations for the shallow size of objects in memory.
 *
 * Determining the actual shallow size of an object in memory is hard, as it changes for each VM
 * implementation, depending on the various memory layout optimizations and bit alignment.
 *
 * More on this topic: https://dev.to/pyricau/the-real-size-of-android-objects-1i2e
 */
internal class ShallowSizeCalculator(private val graph: HeapGraph) {

  // Object arrays: header = RoundUp(kFirstElementOffset=12, component_size).
  // Component size for object arrays equals identifierByteSize (4B or 8B), so
  // all object arrays in a heap share the same header. Primitive arrays compute
  // their header locally because the component size varies per array type.
  // See art/runtime/mirror/array.h (DataOffset, kFirstElementOffset)
  //     art/runtime/mirror/array-inl.h (Array::SizeOf)
  private val arrayHeader: Int = if (graph.identifierByteSize == 8) 16 else 12

  fun computeShallowSize(objectId: Long): Int {
    return when (val heapObject = graph.findObjectById(objectId)) {
      is HeapInstance -> {
        if (heapObject.instanceClassName == "java.lang.String") {
          // In PathFinder we ignore the value field of String instances when building the dominator
          // tree, so we add that size back here.
          val valueObjectId =
            heapObject["java.lang.String", "value"]?.value?.asNonNullObjectId
          heapObject.byteSize + if (valueObjectId != null) {
            computeShallowSize(valueObjectId)
          } else {
            0
          }
        } else {
          // Total byte size of fields for instances of this class, as registered in the class dump.
          // The actual memory layout likely differs.
          heapObject.byteSize
        }
      }
      // Array header + element data.
      // HPROF OBJECT_ARRAY_DUMP and PRIMITIVE_ARRAY_DUMP records store only element data,
      // not the object header, so heapObject.byteSize is element data only. We add the header.
      is HeapObjectArray -> {
        if (heapObject.isSkippablePrimitiveWrapperArray) {
          // In PathFinder we ignore references from primitive wrapper arrays when building the
          // dominator tree, so we add that size back here.
          val elementIds = heapObject.readRecord().elementIds
          val shallowSize = arrayHeader + elementIds.size * graph.identifierByteSize
          val firstNonNullElement = elementIds.firstOrNull { it != ValueHolder.NULL_REFERENCE }
          if (firstNonNullElement != null) {
            val sizeOfOneElement = computeShallowSize(firstNonNullElement)
            val countOfNonNullElements = elementIds.count { it != ValueHolder.NULL_REFERENCE }
            shallowSize + (sizeOfOneElement * countOfNonNullElements)
          } else {
            shallowSize
          }
        } else {
          arrayHeader + heapObject.byteSize
        }
      }
      is HeapPrimitiveArray -> {
        // Primitive component size determines alignment: long/double (8B) → 16B header, else 12B.
        val primitiveArrayHeader = if (heapObject.primitiveType.byteSize == 8) 16 else 12
        primitiveArrayHeader + heapObject.byteSize
      }
      is HeapClass -> {
        // mirror::Class in ART is variable-size: fixed_header + static_field_values.
        // (art/runtime/mirror/class.h: class_size_ tracks the total; fields_[] at the end)
        //
        // In Android HPROF, class objects have NO INSTANCE_DUMP record — each class is
        // represented solely by its CLASS_DUMP record (art/runtime/hprof/hprof.cc).
        // java.lang.Class.instanceByteSize == 0 in Android HPROF because ART special-cases it,
        // so we cannot recover the fixed mirror::Class header (~100–130 B of internal C++
        // fields: vtable, class_flags_, dex_cache_, etc.) from HPROF data.
        //
        // We therefore sum two approximable portions:
        //
        // 1. Static field values: the variable in-object portion of class_size_ — readable
        //    from CLASS_DUMP via readRecordStaticFields().
        //
        // 2. ArtField metadata: each declared field (instance or static) has a corresponding
        //    ArtField object in ART's LinearAlloc native memory — NOT on the Java heap, hence
        //    absent from HPROF. sizeof(ArtField) == 16 on all Android targets: four uint32_t
        //    fields (declaring_class_ GcRoot, access_flags_, field_dex_idx_, offset_).
        //    (art/runtime/art_field.h)
        //    The count is cheaply readable from CLASS_DUMP, so we include this native cost.
        //
        // What remains uncaptured: the fixed mirror::Class header and ArtMethod metadata.
        val staticFieldRecords = heapObject.readRecordStaticFields()
        val staticValueSize = staticFieldRecords.sumOf { holderSize(it.value) }
        val artFieldBytes = (staticFieldRecords.size + heapObject.readInstanceFieldCount()) * 16
        staticValueSize + artFieldBytes
      }
    }
  }

  private fun holderSize(holder: ValueHolder): Int = when (holder) {
    is ReferenceHolder -> graph.identifierByteSize
    is BooleanHolder -> 1
    is CharHolder -> 2
    is FloatHolder -> 4
    is DoubleHolder -> 8
    is ByteHolder -> 1
    is ShortHolder -> 2
    is IntHolder -> 4
    is LongHolder -> 8
  }
}
