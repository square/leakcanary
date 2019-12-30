package shark

class HeapClassMemberField private constructor(
  val name: String,
  private val _primitiveType: PrimitiveType?
) {

  val isReference: Boolean get() = _primitiveType == null
  val isPrimitiveType: Boolean get() = _primitiveType != null

  val primitiveType: PrimitiveType get() = _primitiveType!!

  companion object {
    fun create(
      name: String,
      hprofType: Int
    ): HeapClassMemberField {
      val primitiveType =
        if (hprofType == PrimitiveType.REFERENCE_HPROF_TYPE) {
          null
        } else {
          PrimitiveType.primitiveTypeByHprofType[hprofType]
        }

      return HeapClassMemberField(name, primitiveType)
    }
  }

}