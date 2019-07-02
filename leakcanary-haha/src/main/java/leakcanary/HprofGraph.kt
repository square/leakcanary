package leakcanary

import leakcanary.GraphObjectRecord.GraphClassRecord
import leakcanary.GraphObjectRecord.GraphInstanceRecord
import leakcanary.GraphObjectRecord.GraphObjectArrayRecord
import leakcanary.GraphObjectRecord.GraphPrimitiveArrayRecord
import leakcanary.HeapValue.ObjectReference
import leakcanary.ObjectIdMetadata.CLASS
import leakcanary.ObjectIdMetadata.STRING
import leakcanary.Record.HeapDumpRecord.ObjectRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import okio.Buffer

/**
 * Enables navigation through the Hprof graph of objects.
 */
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

  fun readGraphObjectRecord(objectId: Long): GraphObjectRecord {
    return wrapObject(parser.retrieveRecordById(objectId))
  }

  private fun wrapObject(record: ObjectRecord): GraphObjectRecord {
    return when (record) {
      is ClassDumpRecord -> GraphClassRecord(this, record)
      is InstanceDumpRecord -> GraphInstanceRecord(this, record)
      is ObjectArrayDumpRecord -> GraphObjectArrayRecord(this, record)
      is PrimitiveArrayDumpRecord -> GraphPrimitiveArrayRecord(record)
    }
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

  /**
   * This API should eventually be removed.
   */
  fun objectIdMetadata(objectId: Long): ObjectIdMetadata = parser.objectIdMetadata(objectId)

  fun computeShallowSize(record: ObjectRecord): Int {
    return when (record) {
      is InstanceDumpRecord -> {
        val classRecord = readObjectRecord(record.classId) as ClassDumpRecord
        // Note: instanceSize is the sum of shallow size through the class hierarchy
        classRecord.instanceSize
      }
      is ObjectArrayDumpRecord -> record.elementIds.size * parser.idSize
      is BooleanArrayDump -> record.array.size * HprofReader.BOOLEAN_SIZE
      is CharArrayDump -> record.array.size * HprofReader.CHAR_SIZE
      is FloatArrayDump -> record.array.size * HprofReader.FLOAT_SIZE
      is DoubleArrayDump -> record.array.size * HprofReader.DOUBLE_SIZE
      is ByteArrayDump -> record.array.size * HprofReader.BYTE_SIZE
      is ShortArrayDump -> record.array.size * HprofReader.SHORT_SIZE
      is IntArrayDump -> record.array.size * HprofReader.INT_SIZE
      is LongArrayDump -> record.array.size * HprofReader.LONG_SIZE
      else -> {
        throw IllegalStateException("Unexpected record $record")
      }
    }
  }

}