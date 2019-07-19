package leakcanary

import leakcanary.HeapValue.ObjectReference
import leakcanary.PrimitiveType.BOOLEAN
import leakcanary.PrimitiveType.BYTE
import leakcanary.PrimitiveType.CHAR
import leakcanary.PrimitiveType.DOUBLE
import leakcanary.PrimitiveType.FLOAT
import leakcanary.PrimitiveType.INT
import leakcanary.PrimitiveType.LONG
import leakcanary.PrimitiveType.SHORT
import leakcanary.Record.HeapDumpRecord.ObjectRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
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
import leakcanary.internal.IndexedObject.IndexedClass
import leakcanary.internal.IndexedObject.IndexedInstance
import leakcanary.internal.IndexedObject.IndexedObjectArray
import leakcanary.internal.IndexedObject.IndexedPrimitiveArray
import java.nio.charset.Charset
import kotlin.reflect.KClass

/**
 * Represents an object in the heap dump and provides navigation capabilities.
 */
sealed class GraphObjectRecord {

  abstract val graph: HprofGraph

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

  /**
   * Represents a class in the heap dump and provides navigation capabilities.
   */
  class GraphClassRecord internal constructor(
    override val graph: HprofGraph,
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
        if (indexedObject.superClassId == HeapValue.NULL_REFERENCE) return null
        return graph.findObjectByObjectId(indexedObject.superClassId) as GraphClassRecord
      }

    val classHierarchy: Sequence<GraphClassRecord>
      get() = generateSequence(this) { it.superClass }

    val directInstances: Sequence<GraphInstanceRecord>
      get() = graph.instances.filter { it.indexedObject.classId == objectId }

    fun readStaticFields(): Sequence<GraphField> {
      return readRecord().staticFields.asSequence()
          .map { fieldRecord ->
            GraphField(
                this, graph.staticFieldName(fieldRecord), GraphHeapValue(graph, fieldRecord.value)
            )
          }
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

    override fun toString(): String {
      return "record of class $name"
    }
  }

  /**
   * Represents an instance in the heap dump and provides navigation capabilities.
   */
  class GraphInstanceRecord internal constructor(
    override val graph: HprofGraph,
    internal val indexedObject: IndexedInstance,
    override val objectId: Long,
    val isPrimitiveWrapper: Boolean
  ) : GraphObjectRecord() {

    val size
      get() = instanceClass.instanceSize

    override fun readRecord(): InstanceDumpRecord {
      return graph.readInstanceDumpRecord(objectId, indexedObject)
    }

    infix fun instanceOf(className: String): Boolean {
      var currentClassId = indexedObject.classId
      while (currentClassId != HeapValue.NULL_REFERENCE) {
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
        return graph.findObjectByObjectId(indexedObject.classId) as GraphClassRecord
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

      // JVM strings don't have a count field.
      val count = this["java.lang.String", "count"]?.value?.asInt
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
          val offset = this["java.lang.String", "offset"]?.value?.asInt

          val chars = if (count != null && offset != null) {
            // Handle heap dumps where all primitive arrays have been replaced with empty arrays,
            // e.g. with HprofPrimitiveArrayStripper
            val toIndex = if (offset + count > valueRecord.array.size) {
              valueRecord.array.size
            } else offset + count
            valueRecord.array.copyOfRange(offset, toIndex)
          } else {
            valueRecord.array
          }
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

    override fun toString(): String {
      return "instance @$objectId of $className"
    }
  }

  /**
   * Represents an object array in the heap dump and provides navigation capabilities.
   */
  class GraphObjectArrayRecord internal constructor(
    override val graph: HprofGraph,
    private val indexedObject: IndexedObjectArray,
    override val objectId: Long,
    val isPrimitiveWrapperArray: Boolean
  ) : GraphObjectRecord() {

    val arrayClassName: String
      get() = graph.className(indexedObject.arrayClassId)

    fun readSize(): Int {
      return readRecord().elementIds.size * graph.idSize
    }

    override fun readRecord(): ObjectArrayDumpRecord {
      return graph.readObjectArrayDumpRecord(objectId, indexedObject)
    }

    fun readElements(): Sequence<GraphHeapValue> {
      return readRecord().elementIds.asSequence()
          .map { GraphHeapValue(graph, ObjectReference(it)) }
    }

    override fun toString(): String {
      return "object array @$objectId of $arrayClassName"
    }
  }

  /**
   * Represents a primitive array in the heap dump and provides navigation capabilities.
   */
  class GraphPrimitiveArrayRecord internal constructor(
    override val graph: HprofGraph,
    private val indexedObject: IndexedPrimitiveArray,
    override val objectId: Long
  ) : GraphObjectRecord() {

    fun readSize(): Int {
      return when (val record = readRecord()) {
        is BooleanArrayDump -> record.array.size * HprofReader.BOOLEAN_SIZE
        is CharArrayDump -> record.array.size * HprofReader.CHAR_SIZE
        is FloatArrayDump -> record.array.size * HprofReader.FLOAT_SIZE
        is DoubleArrayDump -> record.array.size * HprofReader.DOUBLE_SIZE
        is ByteArrayDump -> record.array.size * HprofReader.BYTE_SIZE
        is ShortArrayDump -> record.array.size * HprofReader.SHORT_SIZE
        is IntArrayDump -> record.array.size * HprofReader.INT_SIZE
        is LongArrayDump -> record.array.size * HprofReader.LONG_SIZE
      }
    }

    val primitiveType: PrimitiveType
      get() = indexedObject.primitiveType

    val arrayClassName: String
      get() = when (primitiveType) {
        BOOLEAN -> "boolean[]"
        CHAR -> "char[]"
        FLOAT -> "float[]"
        DOUBLE -> "double[]"
        BYTE -> "byte[]"
        SHORT -> "short[]"
        INT -> "int[]"
        LONG -> "long[]"
      }

    override fun readRecord(): PrimitiveArrayDumpRecord {
      return graph.readPrimitiveArrayDumpRecord(objectId, indexedObject)
    }

    override fun toString(): String {
      return "primitive array @$objectId of $arrayClassName"
    }
  }

}