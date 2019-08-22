package shark

/**
 * A primitive type in the prof.
 */
enum class PrimitiveType(
  /**
   * The hprof defined "basic type".
   */
  val hprofType: Int,
  /**
   * The size in bytes for each value of that type.
   */
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
    /**
     * The hprof defined "basic type" for references.
     */
    const val REFERENCE_HPROF_TYPE = 2

    val byteSizeByHprofType = values().map { it.hprofType to it.byteSize }.toMap()

    val primitiveTypeByHprofType = values().map { it.hprofType to it }.toMap()
  }
}