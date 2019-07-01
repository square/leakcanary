package leakcanary

import leakcanary.GraphObjectRecord.GraphClassRecord
import leakcanary.HeapValue.ObjectReference
import leakcanary.ObjectIdMetadata.CLASS
import leakcanary.ObjectIdMetadata.STRING
import leakcanary.Record.HeapDumpRecord.ObjectRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import okio.Buffer

class HprofGraph(private val parser: HprofParser) {

  fun readClass(className: String): GraphClassRecord? {
    val classId = parser.classId(className)
    return if (classId == null) {
      null
    } else {
      GraphClassRecord(this, readObjectRecord(classId) as ClassDumpRecord)
    }
  }

  fun readObjectRecord(objectId: Long): ObjectRecord {
    return parser.retrieveRecordById(objectId)
  }

  fun className(classId: Long): String {
    return parser.className(classId)
  }

  fun fieldName(fieldRecord: FieldRecord): String {
    return parser.hprofStringById(fieldRecord.nameStringId)
  }

  fun staticFieldName(fieldRecord: StaticFieldRecord): String {
    return parser.hprofStringById(fieldRecord.nameStringId)
  }

  fun createFieldValuesReader(record: InstanceDumpRecord): FieldValuesReader {
    val buffer = Buffer()
    buffer.write(record.fieldValues)

    val reader = HprofReader(buffer, 0, parser.idSize)

    return object : FieldValuesReader {
      override fun readValue(field: FieldRecord): HeapValue {
        return reader.readValue(field.type)
      }
    }
  }

  fun isJavaString(record: InstanceDumpRecord): Boolean {
    return parser.objectIdMetadata(record.id) == STRING
  }

  fun referencesJavaString(reference: ObjectReference): Boolean {
    return !reference.isNull && parser.objectIdMetadata(reference.value) == STRING
  }

  fun referencesClass(reference: ObjectReference): Boolean {
    return !reference.isNull && parser.objectIdMetadata(reference.value) == CLASS
  }
}