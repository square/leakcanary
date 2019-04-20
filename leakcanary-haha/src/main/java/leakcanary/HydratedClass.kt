package leakcanary

import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord

class HydratedClass(
  val record: ClassDumpRecord,
  val className: String,
  val staticFieldNames: List<String>,
  val fieldNames: List<String>
) {
  fun <T : HeapValue> staticFieldValue(name: String): T {
    return staticFieldValueOrNull(name) ?: throw IllegalArgumentException(
        "Could not find static field $name in class $className with id ${record.id} and static fields $staticFieldNames"
    )
  }

  fun fieldType(name: String): Int {
    fieldNames.forEachIndexed { index, fieldName ->
      if (fieldName == name) {
        return record.fields[index].type
      }
    }
    throw IllegalArgumentException(
        "Could not find field $name in class $className with id ${record.id} and fields $fieldNames"
    )
  }

  fun <T : HeapValue> staticFieldValueOrNull(name: String): T? {
    staticFieldNames.forEachIndexed { fieldIndex, fieldName ->
      if (fieldName == name) {
        @Suppress("UNCHECKED_CAST")
        return record.staticFields[fieldIndex].value as T
      }
    }
    return null
  }

  fun hasStaticField(name: String): Boolean {
    staticFieldNames.forEach { fieldName ->
      if (fieldName == name) {
        return true
      }
    }
    return false
  }
}