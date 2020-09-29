package shark.internal

import shark.PrimitiveType

internal sealed class IndexedObject {
  abstract val position: Long
  abstract val recordSize: Long

  class IndexedClass(
    override val position: Long,
    val superclassId: Long,
    val instanceSize: Int,
    override val recordSize: Long,
    val fieldsIndex: Int
  ) : IndexedObject()

  class IndexedInstance(
    override val position: Long,
    val classId: Long,
    override val recordSize: Long
  ) : IndexedObject()

  class IndexedObjectArray(
    override val position: Long,
    val arrayClassId: Long,
    override val recordSize: Long
  ) : IndexedObject()

  class IndexedPrimitiveArray(
    override val position: Long,
    primitiveType: PrimitiveType,
    override val recordSize: Long
  ) : IndexedObject() {
    private val primitiveTypeOrdinal: Byte = primitiveType.ordinal.toByte()
    val primitiveType: PrimitiveType
      get() = PrimitiveType.values()[primitiveTypeOrdinal.toInt()]
  }

}