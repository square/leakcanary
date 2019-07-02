package leakcanary

import leakcanary.Record.HeapDumpRecord.ObjectRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import java.nio.charset.Charset
import kotlin.reflect.KClass

sealed class GraphObjectRecord {
  abstract val record: ObjectRecord

  val asClass: GraphClassRecord?
    get() = if (this is GraphClassRecord) this else null

  val asInstance: GraphInstanceRecord?
    get() = if (this is GraphInstanceRecord) this else null

  val asObjectArray: GraphObjectArrayRecord?
    get() = if (this is GraphObjectArrayRecord) this else null

  val asPrimitiveArray: GraphPrimitiveArrayRecord?
    get() = if (this is GraphPrimitiveArrayRecord) this else null

  class GraphClassRecord(
    private val graph: HprofGraph,
    override val record: ClassDumpRecord
  ) : GraphObjectRecord() {

    val name: String
      get() = graph.className(record.id)

    val simpleName: String
      get() {
        val className = this.name
        val separator = className.lastIndexOf('.')
        return if (separator == -1) {
          className
        } else {
          className.substring(separator + 1)
        }
      }

    fun readSuperClass(): GraphClassRecord? {
      if (record.superClassId == 0L) return null
      val superClassRecord = graph.readObjectRecord(record.superClassId) as ClassDumpRecord
      return GraphClassRecord(graph, superClassRecord)
    }

    val staticFields
      get(): List<GraphField> {
        val fields = mutableListOf<GraphField>()
        for (fieldRecord in record.staticFields) {
          fields += GraphField(
              this, graph.staticFieldName(fieldRecord), GraphHeapValue(graph, fieldRecord.value)
          )
        }
        return fields
      }

    operator fun get(fieldName: String): GraphField? {
      for (fieldRecord in record.staticFields) {
        if (graph.staticFieldName(fieldRecord) == fieldName) {
          return GraphField(
              this, graph.staticFieldName(fieldRecord), GraphHeapValue(graph, fieldRecord.value)
          )
        }
      }
      return null
    }
  }

  class GraphInstanceRecord(
    private val graph: HprofGraph,
    override val record: InstanceDumpRecord
  ) : GraphObjectRecord() {

    infix fun instanceOf(className: String): Boolean {
      var currentClassId = record.classId
      while (currentClassId != 0L) {
        if (graph.className(currentClassId) == className) {
          return true
        }

        val currentClassRecord = graph.readObjectRecord(currentClassId) as ClassDumpRecord
        currentClassId = currentClassRecord.superClassId
      }
      return false
    }

    infix fun instanceOf(expectedClass: KClass<*>) =
      this instanceOf expectedClass.java.name

    operator fun get(fieldName: String): GraphField? {
      val fieldReader = graph.createFieldValuesReader(record)

      var currentClassId = record.classId

      do {
        val classRecord = graph.readObjectRecord(currentClassId) as ClassDumpRecord

        for (fieldRecord in classRecord.fields) {
          val fieldValue = fieldReader.readValue(fieldRecord)

          if (graph.fieldName(fieldRecord) == fieldName) {
            return GraphField(
                GraphClassRecord(graph, classRecord), fieldName, GraphHeapValue(graph, fieldValue)
            )
          }
        }
        currentClassId = classRecord.superClassId
      } while (currentClassId != 0L)
      return null
    }

    val className: String
      get() = graph.className(record.classId)

    val simpleClassName: String
      get() {
        val className = this.className
        val separator = className.lastIndexOf('.')
        return if (separator == -1) {
          className
        } else {
          className.substring(separator + 1)
        }
      }

    fun readClass(): GraphClassRecord {
      val classRecord = graph.readObjectRecord(record.classId) as ClassDumpRecord
      return GraphClassRecord(graph, classRecord)
    }

    fun readFields(): List<List<GraphField>> {
      val allFields = mutableListOf<List<GraphField>>()
      val fieldReader = graph.createFieldValuesReader(record)

      var currentClassId = record.classId
      do {
        val classFields = mutableListOf<GraphField>()
        allFields += classFields

        val classRecord = graph.readObjectRecord(currentClassId) as ClassDumpRecord

        for (fieldRecord in classRecord.fields) {
          val fieldName = graph.fieldName(fieldRecord)
          val fieldValue = fieldReader.readValue(fieldRecord)
          classFields += GraphField(
              GraphClassRecord(graph, classRecord), fieldName, GraphHeapValue(graph, fieldValue)
          )
        }
        currentClassId = classRecord.superClassId
      } while (currentClassId != 0L)
      return allFields
    }

    fun readAsJavaString(): String? {
      if (!graph.isJavaString(record)) {
        return null
      }
      val count = this["count"]!!.value.asInt!!
      if (count == 0) {
        return ""
      }

      // Prior to API 26 String.value was a char array.
      // Since API 26 String.value is backed by native code. The vast majority of strings in a
      // heap dump are backed by a byte array, but we still find a few backed by a char array.
      when (val valueRecord = this["value"]!!.value.readObjectRecord()!!.record) {
        is CharArrayDump -> {
          // < API 23
          // As of Marshmallow, substrings no longer share their parent strings' char arrays
          // eliminating the need for String.offset
          // https://android-review.googlesource.com/#/c/83611/
          val offset = this["offset"]?.value?.asInt ?: 0

          val chars = valueRecord.array.copyOfRange(offset, offset + count)
          return String(chars)
        }
        is ByteArrayDump -> {
          return String(valueRecord.array, Charset.forName("UTF-8"))
        }
        else -> throw UnsupportedOperationException(
            "'value' field ${this["value"]!!.value} was expected to be either a char or byte array in string instance with id ${record.id}"
        )
      }
    }
  }

  class GraphObjectArrayRecord(
    private val graph: HprofGraph,
    override val record: ObjectArrayDumpRecord
  ) : GraphObjectRecord() {
  }

  class GraphPrimitiveArrayRecord(
    override val record: PrimitiveArrayDumpRecord
  ) : GraphObjectRecord() {
  }

}