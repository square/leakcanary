package leakcanary.hprof

import okio.ByteString

enum class Type(
  val label: String?,
  val numBytes: Int
) {
  OBJECT(null, -1), // variable
  BOOLEAN("boolean", 1),
  CHAR("char", 2),
  FLOAT("float", 4),
  DOUBLE("double", 8),
  BYTE("byte", 1),
  SHORT("short", 2),
  INT("int", 4),
  LONG("long", 8);

  companion object {
    @JvmStatic
    fun getType(type: Byte): Type {
      return when (type.toInt()) {
        2 -> OBJECT
        4 -> BOOLEAN
        5 -> CHAR
        6 -> FLOAT
        7 -> DOUBLE
        8 -> BYTE
        9 -> SHORT
        10 -> INT
        11 -> LONG
        else -> throw IllegalArgumentException("Unexpected type in heap dump: $type")
      }
    }
  }
}

internal class Root

internal class InstanceField(
  val stringId: Long,
  val type: Type
)

internal class Instance(
  val id: Long,
  val classId: Long,
  val fieldBytes: ByteString
)

internal class ClassInfo(
  val classObjectId: Long,
  val superClassObjectID: Long,
  val instanceSize: Int,
  val instanceFields: Array<InstanceField>
)
