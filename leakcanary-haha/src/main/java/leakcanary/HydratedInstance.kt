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
  fun <T : HeapValue> fieldValue(name: String): T {
    return fieldValueOrNull(name) ?: throw IllegalArgumentException(
        "Could not find field $name in instance with id ${record.id}"
    )
  }

  fun <T : HeapValue> fieldValueOrNull(name: String): T? {
    classHierarchy.forEachIndexed { classIndex, hydratedClass ->
      hydratedClass.fieldNames.forEachIndexed { fieldIndex, fieldName ->
        if (fieldName == name) {
          @Suppress("UNCHECKED_CAST")
          return fieldValues[classIndex][fieldIndex] as T
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
}