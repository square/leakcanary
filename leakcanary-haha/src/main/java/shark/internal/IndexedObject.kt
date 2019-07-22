package shark.internal

import shark.PrimitiveType

internal sealed class IndexedObject {
  abstract val position: Long

  class IndexedClass(
    override val position: Long,
    val superclassId: Long,
    val instanceSize: Int
  ) : IndexedObject()

  class IndexedInstance(
    override val position: Long,
    val classId: Long
  ) : IndexedObject()

  class IndexedObjectArray(
    override val position: Long,
    val arrayClassId: Long
  ) : IndexedObject()

  class IndexedPrimitiveArray(
    override val position: Long,
    primitiveType: PrimitiveType
  ) : IndexedObject() {
    private val primitiveTypeOrdinal: Byte = primitiveType.ordinal.toByte()
    val primitiveType: PrimitiveType
      get() = PrimitiveType.values()[primitiveTypeOrdinal.toInt()]
  }

}