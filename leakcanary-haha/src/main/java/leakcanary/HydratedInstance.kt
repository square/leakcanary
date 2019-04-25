package leakcanary

import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord

class HydratedInstance(
  val record: InstanceDumpRecord,
  val classHierarchy: List<HydratedClass>,
  /**
   * One list of field values per class
   */
  val fieldValues: List<List<HeapValue>>
) {
  inline fun <reified T : HeapValue> fieldValue(name: String): T {
    return fieldValueOrNull(name) ?: throw IllegalArgumentException(
        "Could not find field $name in instance with id ${record.id}"
    )
  }

  inline fun <reified T : HeapValue> fieldValueOrNull(name: String): T? {
    classHierarchy.forEachIndexed { classIndex, hydratedClass ->
      hydratedClass.fieldNames.forEachIndexed { fieldIndex, fieldName ->
        if (fieldName == name) {
          val fieldValue = fieldValues[classIndex][fieldIndex]
          if (fieldValue is T) {
            return fieldValue
          } else return null
        }
      }
    }
    return null
  }

  fun hasField(name: String): Boolean {
    classHierarchy.forEach { hydratedClass ->
      hydratedClass.fieldNames.forEach { fieldName ->
        if (fieldName == name) {
          return true
        }
      }
    }
    return false
  }

  fun isInstanceOf(className: String): Boolean {
    return classHierarchy.any { it.className == className }
  }
}