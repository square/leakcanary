package shark

enum class PrimitiveType(
  val hprofType: Int,
  val byteSize: Int
) {
  BOOLEAN(4, 1),
  CHAR(5, 2),
  FLOAT(6, 4),
  DOUBLE(7, 8),
  BYTE(8, 1),
  SHORT(9, 2),
  INT(10, 4),
  LONG(11, 8);

  companion object {
    const val REFERENCE_HPROF_TYPE = 2

    val byteSizeByHprofType = values().map { it.hprofType to it.byteSize }.toMap()
  }
}