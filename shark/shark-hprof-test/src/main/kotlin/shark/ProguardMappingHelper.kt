package shark

class ProguardMappingHelper(
  private val proguardMapping: ProguardMapping
) {
  fun clazz(
    className: Pair<String, String>,
    fieldsBlock: Class.() -> Unit = {}
  ) {
    val clazz = Class(className)
    fieldsBlock(clazz)
    proguardMapping.addMapping(clazz.nameMapping.second, clazz.nameMapping.first)
    clazz.fieldMappings.forEach { field ->
      proguardMapping.addMapping("${clazz.nameMapping.second}.${field.second}", field.first)
    }
  }

  inner class Class(val nameMapping: Pair<String, String>) {
    val fieldMappings = mutableSetOf<Pair<String, String>>()
  }

  fun Class.field(block: () -> Pair<String, String>) {
    fieldMappings.add(block())
  }
}

fun ProguardMapping.create(block: ProguardMappingHelper.() -> Unit): ProguardMapping {
  block(ProguardMappingHelper(this))
  return this
}