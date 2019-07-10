package leakcanary

import leakcanary.Record.HeapDumpRecord.ObjectRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import leakcanary.internal.IndexedObject.IndexedClass
import leakcanary.internal.IndexedObject.IndexedInstance
import leakcanary.internal.IndexedObject.IndexedObjectArray
import leakcanary.internal.IndexedObject.IndexedPrimitiveArray
import java.nio.charset.Charset
import kotlin.reflect.KClass

sealed class GraphObjectRecord {

  abstract val objectId: Long

  abstract fun readRecord(): ObjectRecord

  val asClass: GraphClassRecord?
    get() = if (this is GraphClassRecord) this else null

  val asInstance: GraphInstanceRecord?
    get() = if (this is GraphInstanceRecord) this else null

  val asObjectArray: GraphObjectArrayRecord?
    get() = if (this is GraphObjectArrayRecord) this else null

  val asPrimitiveArray: GraphPrimitiveArrayRecord?
    get() = if (this is GraphPrimitiveArrayRecord) this else null

  class GraphClassRecord internal constructor(
    private val graph: HprofGraph,
    private val indexedObject: IndexedClass,
    override val objectId: Long
  ) : GraphObjectRecord() {
    override fun readRecord(): ClassDumpRecord {
      return graph.readClassDumpRecord(objectId, indexedObject)
    }

    val name: String
      get() = graph.className(objectId)

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

    val instanceSize: Int
      get() = indexedObject.instanceSize

    val superClass: GraphClassRecord?
      get() {
        if (indexedObject.superClassId == 0L) return null
        return graph.indexedObject(indexedObject.superClassId) as GraphClassRecord
      }

    val classHierarchy: Sequence<GraphClassRecord>
      get() = generateSequence(this) { it.superClass }

    fun readStaticFields(): List<GraphField> {
      val fields = mutableListOf<GraphField>()
      for (fieldRecord in readRecord().staticFields) {
        fields += GraphField(
            this, graph.staticFieldName(fieldRecord), GraphHeapValue(graph, fieldRecord.value)
        )
      }
      return fields
    }

    operator fun get(fieldName: String): GraphField? {
      for (fieldRecord in readRecord().staticFields) {
        if (graph.staticFieldName(fieldRecord) == fieldName) {
          return GraphField(
              this, graph.staticFieldName(fieldRecord), GraphHeapValue(graph, fieldRecord.value)
          )
        }
      }
      return null
    }
  }

  class GraphInstanceRecord internal constructor(
    private val graph: HprofGraph,
    private val indexedObject: IndexedInstance,
    override val objectId: Long,
    val isPrimitiveWrapper: Boolean
  ) : GraphObjectRecord() {
    override fun readRecord(): InstanceDumpRecord {
      return graph.readInstanceDumpRecord(objectId, indexedObject)
    }

    infix fun instanceOf(className: String): Boolean {
      var currentClassId = indexedObject.classId
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

    operator fun get(
      declaringClass: KClass<out Any>,
      fieldName: String
    ): GraphField? {
      return get(declaringClass.java.name, fieldName)
    }

    operator fun get(
      declaringClassName: String,
      fieldName: String
    ): GraphField? {
      return readFields().firstOrNull { field -> field.classRecord.name == declaringClassName && field.name == fieldName }
    }

    val className: String
      get() = graph.className(indexedObject.classId)

    val classSimpleName: String
      get() {
        val className = this.className
        val separator = className.lastIndexOf('.')
        return if (separator == -1) {
          className
        } else {
          className.substring(separator + 1)
        }
      }

    val instanceClass: GraphClassRecord
      get() {
        return graph.indexedObject(indexedObject.classId) as GraphClassRecord
      }

    fun readFields(): Sequence<GraphField> {
      val fieldReader by lazy {
        graph.createFieldValuesReader(readRecord())
      }
      return instanceClass.classHierarchy
          .map { classRecord ->
            classRecord.readRecord()
                .fields.asSequence()
                .map { fieldRecord ->
                  val fieldName = graph.fieldName(fieldRecord)
                  val fieldValue = fieldReader.readValue(fieldRecord)
                  GraphField(classRecord, fieldName, GraphHeapValue(graph, fieldValue))
                }
          }
          .flatten()
    }

    fun readAsJavaString(): String? {
      if (className != "java.lang.String") {
        return null
      }
      val count = this["java.lang.String", "count"]!!.value.asInt!!
      if (count == 0) {
        return ""
      }

      // Prior to API 26 String.value was a char array.
      // Since API 26 String.value is backed by native code. The vast majority of strings in a
      // heap dump are backed by a byte array, but we still find a few backed by a char array.
      when (val valueRecord =
        this["java.lang.String", "value"]!!.value.asObject!!.readRecord()) {
        is CharArrayDump -> {
          // < API 23
          // As of Marshmallow, substrings no longer share their parent strings' char arrays
          // eliminating the need for String.offset
          // https://android-review.googlesource.com/#/c/83611/
          val offset = this["java.lang.String", "offset"]?.value?.asInt ?: 0

          val chars = valueRecord.array.copyOfRange(offset, offset + count)
          return String(chars)
        }
        is ByteArrayDump -> {
          return String(valueRecord.array, Charset.forName("UTF-8"))
        }
        else -> throw UnsupportedOperationException(
            "'value' field ${this["java.lang.String", "value"]!!.value} was expected to be either" +
                " a char or byte array in string instance with id $objectId"
        )
      }
    }
  }

  class GraphObjectArrayRecord internal constructor(
    private val graph: HprofGraph,
    private val indexedObject: IndexedObjectArray,
    override val objectId: Long,
    val isPrimitiveWrapperArray: Boolean
  ) : GraphObjectRecord() {

    val arrayClassName: String
      get() = graph.className(indexedObject.arrayClassId)

    override fun readRecord(): ObjectArrayDumpRecord {
      return graph.readObjectArrayDumpRecord(objectId, indexedObject)
    }

  }

  class GraphPrimitiveArrayRecord internal constructor(
    private val graph: HprofGraph,
    private val indexedObject: IndexedPrimitiveArray,
    override val objectId: Long
  ) : GraphObjectRecord() {
    val primitiveType: PrimitiveType
      get() = indexedObject.primitiveType

    override fun readRecord(): PrimitiveArrayDumpRecord {
      return graph.readPrimitiveArrayDumpRecord(objectId, indexedObject)
    }
  }

}