package leakcanary

import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord

class HydratedClass(
  val record: ClassDumpRecord,
  val className: String,
  val staticFieldNames: List<String>,
  val fieldNames: List<String>
) {
  inline fun <reified T : HeapValue> staticFieldValue(name: String): T {
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

  inline fun <reified T : HeapValue> staticFieldValueOrNull(name: String): T? {
    staticFieldNames.forEachIndexed { fieldIndex, fieldName ->
      if (fieldName == name) {
        val fieldValue = record.staticFields[fieldIndex].value
        return if (fieldValue is T) {
          fieldValue
        } else null
      }
    }
    return null
  }

  operator fun get(name: String): HeapValue? = staticFieldValueOrNull(name)

  fun hasStaticField(name: String): Boolean {
    staticFieldNames.forEach { fieldName ->
      if (fieldName == name) {
        return true
      }
    }
    return false
  }

  val simpleClassName: String
    get() {
      val separator = className.lastIndexOf('.')
      return if (separator == -1) {
        className
      } else {
        className.substring(separator + 1)
      }
    }
}