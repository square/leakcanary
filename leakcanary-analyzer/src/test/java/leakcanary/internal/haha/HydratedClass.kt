package leakcanary.internal.haha

import leakcanary.internal.haha.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord

class HydratedClass(
  val record: ClassDumpRecord,
  val className: String,
  val staticFieldNames: List<String>,
  val fieldNames: List<String>
) {
  fun <T : HeapValue> staticFieldValue(name: String): T {
    return staticFieldValueOrNull(name) ?: throw IllegalArgumentException(
        "Could not find field $name in class with id ${record.id}"
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