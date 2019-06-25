package leakcanary

import leakcanary.HeapValue.ObjectReference
import leakcanary.ObjectIdMetadata.CLASS
import leakcanary.ObjectIdMetadata.INSTANCE
import leakcanary.ObjectIdMetadata.STRING
import leakcanary.Record.HeapDumpRecord.ObjectRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import okio.Buffer
import java.nio.charset.Charset
import kotlin.reflect.KClass

class HprofGraph(private val parser: HprofParser) {

  val HeapValue?.record: ObjectRecord?
    get() = if (this is ObjectReference && !isNull) {
      parser.retrieveRecord(this)
    } else
      null

  val ObjectReference.isString: Boolean
    get() = if (value == 0L) false else parser.objectIdMetadata(value) == STRING

  val ObjectReference.isClass: Boolean
    get() = if (value == 0L) false else parser.objectIdMetadata(value) == CLASS

  val ObjectReference.isInstance: Boolean
    get() = if (value == 0L) false else parser.objectIdMetadata(value) == INSTANCE

  infix fun ObjectRecord?.instanceOf(expectedClass: KClass<*>) =
    this instanceOf expectedClass.java.name

  infix fun ObjectRecord?.instanceOf(className: String): Boolean {
    if (this !is InstanceDumpRecord) {
      return false
    }

    var currentClassId = classId
    while (currentClassId != 0L) {
      if (parser.className(currentClassId) == className) {
        return true
      }
      val currentClassRecord = parser.retrieveRecordById(currentClassId) as ClassDumpRecord
      currentClassId = currentClassRecord.superClassId
    }
    return false
  }

  val ObjectRecord?.string: String?
    get() {
      if (this == null) {
        return null
      } else {
        if (this is InstanceDumpRecord && parser.objectIdMetadata(id) == STRING) {
          val count = this["count"].int!!

          if (count == 0) {
            return ""
          }

          // Prior to API 26 String.value was a char array.
          // Since API 26 String.value is backed by native code. The vast majority of strings in a
          // heap dump are backed by a byte array, but we still find a few backed by a char array.
          when (val valueRecord = this["value"]?.record) {
            is CharArrayDump -> {
              // < API 23
              // As of Marshmallow, substrings no longer share their parent strings' char arrays
              // eliminating the need for String.offset
              // https://android-review.googlesource.com/#/c/83611/
              val offset = this["offset"].int ?: 0

              val chars = valueRecord.array.copyOfRange(offset, offset + count)
              return String(chars)
            }
            is ByteArrayDump -> {
              return String(valueRecord.array, Charset.forName("UTF-8"))
            }
            else -> throw UnsupportedOperationException(
                "'value' field ${this["value"]} was expected to be either a char or byte array in string instance with id $id"
            )
          }
        } else {
          return null
        }
      }
    }

  operator fun ObjectArrayDumpRecord.get(index: Int) = ObjectReference(elementIds[index])

  val InstanceDumpRecord.classRecord
    get() = parser.retrieveRecordById(classId) as ClassDumpRecord

  val ClassDumpRecord.superClassRecord
    get() = if (superClassId == 0L) null else
      parser.retrieveRecordById(superClassId) as ClassDumpRecord

  val ClassDumpRecord.name
    get() = parser.className(id)

  val ClassDumpRecord.staticFieldNames
    get(): List<String> {
      val fieldNames = mutableListOf<String>()
      for (field in staticFields) {
        fieldNames += parser.hprofStringById(field.nameStringId)
      }
      return fieldNames
    }

  operator fun InstanceDumpRecord.get(fieldName: String): HeapValue? {
    val buffer = Buffer()
    buffer.write(fieldValues)
    val valuesReader = HprofReader(buffer, 0, parser.idSize)

    var currentClassId = classId

    do {
      val classRecord = parser.retrieveRecordById(currentClassId) as ClassDumpRecord

      for (fieldRecord in classRecord.fields) {
        val fieldValue = valuesReader.readValue(fieldRecord.type)

        if (parser.hprofStringById(fieldRecord.nameStringId) == fieldName) {
          return fieldValue
        }
      }
      currentClassId = classRecord.superClassId
    } while (currentClassId != 0L)
    return null
  }

  val InstanceDumpRecord.fields: List<Map<String, HeapValue>>
    get() {
      val allFields = mutableListOf<Map<String, HeapValue>>()
      val buffer = Buffer()
      buffer.write(fieldValues)
      val valuesReader = HprofReader(buffer, 0, parser.idSize)

      var currentClassId = classId

      do {

        val classFields = mutableMapOf<String, HeapValue>()
        allFields += classFields

        val classRecord = parser.retrieveRecordById(currentClassId) as ClassDumpRecord

        for (fieldRecord in classRecord.fields) {
          val fieldValue = valuesReader.readValue(fieldRecord.type)
          classFields[parser.hprofStringById(fieldRecord.nameStringId)] = fieldValue
        }
        currentClassId = classRecord.superClassId
      } while (currentClassId != 0L)
      return allFields
    }

  infix fun ObjectRecord?.directInstanceOf(className: String): Boolean {
    return this is InstanceDumpRecord && parser.className(classId) == className
  }

}