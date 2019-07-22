package shark

import shark.HprofRecord.HeapDumpRecord.ObjectRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import shark.PrimitiveType.BOOLEAN
import shark.PrimitiveType.BYTE
import shark.PrimitiveType.CHAR
import shark.PrimitiveType.DOUBLE
import shark.PrimitiveType.FLOAT
import shark.PrimitiveType.INT
import shark.PrimitiveType.LONG
import shark.PrimitiveType.SHORT
import shark.ValueHolder.ReferenceHolder
import shark.internal.IndexedObject.IndexedClass
import shark.internal.IndexedObject.IndexedInstance
import shark.internal.IndexedObject.IndexedObjectArray
import shark.internal.IndexedObject.IndexedPrimitiveArray
import java.nio.charset.Charset
import kotlin.reflect.KClass

/**
 * Represents an object in the heap dump and provides navigation capabilities.
 */
sealed class HeapObject {

  abstract val graph: HeapGraph

  abstract val objectId: Long

  abstract fun readRecord(): ObjectRecord

  val asClass: HeapClass?
    get() = if (this is HeapClass) this else null

  val asInstance: HeapInstance?
    get() = if (this is HeapInstance) this else null

  val asObjectArray: HeapObjectArray?
    get() = if (this is HeapObjectArray) this else null

  val asPrimitiveArray: HeapPrimitiveArray?
    get() = if (this is HeapPrimitiveArray) this else null

  /**
   * Represents a class in the heap dump and provides navigation capabilities.
   */
  class HeapClass internal constructor(
    override val graph: HeapGraph,
    private val indexedObject: IndexedClass,
    override val objectId: Long
  ) : HeapObject() {

    override fun readRecord(): ClassDumpRecord {
      return graph.readClassDumpRecord(objectId, indexedObject)
    }

    val name: String
      get() = graph.className(objectId)

    val simpleName: String
      get() = classSimpleName(name)

    val instanceSize: Int
      get() = indexedObject.instanceSize

    fun readFieldsSize(): Int {
      return readRecord()
          .fields.sumBy {
        if (it.type == PrimitiveType.REFERENCE_HPROF_TYPE) {
          graph.objectIdByteSize
        } else PrimitiveType.byteSizeByHprofType.getValue(it.type)
      }
    }

    val superclass: HeapClass?
      get() {
        if (indexedObject.superclassId == ValueHolder.NULL_REFERENCE) return null
        return graph.findObjectById(indexedObject.superclassId) as HeapClass
      }

    val classHierarchy: Sequence<HeapClass>
      get() = generateSequence(this) { it.superclass }

    val subclasses: Sequence<HeapClass>
      get() = graph.classes.filter { it subclassOf this }

    infix fun superclassOf(subclass: HeapClass): Boolean {
      return subclass.classHierarchy.any { it.objectId == objectId }
    }

    infix fun subclassOf(superclass: HeapClass): Boolean {
      return classHierarchy.any { it.objectId == superclass.objectId }
    }

    /**
     * All instances of this class, including instances of subclasses of this class.
     */
    val instances: Sequence<HeapInstance>
      get() = graph.instances.filter { it instanceOf this }

    /**
     * All direct instances of this class, ie excluding any instance of subclasses of this class.
     */
    val directInstances: Sequence<HeapInstance>
      get() = graph.instances.filter { it.indexedObject.classId == objectId }

    fun readStaticFields(): Sequence<HeapClassField> {
      return readRecord().staticFields.asSequence()
          .map { fieldRecord ->
            HeapClassField(
                this, graph.staticFieldName(fieldRecord), HeapValue(graph, fieldRecord.value)
            )
          }
    }

    fun readStaticField(fieldName: String): HeapClassField? {
      for (fieldRecord in readRecord().staticFields) {
        if (graph.staticFieldName(fieldRecord) == fieldName) {
          return HeapClassField(
              this, graph.staticFieldName(fieldRecord), HeapValue(graph, fieldRecord.value)
          )
        }
      }
      return null
    }

    operator fun get(fieldName: String) = readStaticField(fieldName)

    override fun toString(): String {
      return "record of class $name"
    }
  }

  /**
   * Represents an instance in the heap dump and provides navigation capabilities.
   */
  class HeapInstance internal constructor(
    override val graph: HeapGraph,
    internal val indexedObject: IndexedInstance,
    override val objectId: Long,
    val isPrimitiveWrapper: Boolean
  ) : HeapObject() {

    val size
      get() = instanceClass.instanceSize

    override fun readRecord(): InstanceDumpRecord {
      return graph.readInstanceDumpRecord(objectId, indexedObject)
    }

    infix fun instanceOf(className: String): Boolean =
      instanceClass.classHierarchy.any { it.name == className }

    infix fun instanceOf(expectedClass: KClass<*>) =
      this instanceOf expectedClass.java.name

    infix fun instanceOf(expectedClass: HeapClass) =
      instanceClass.classHierarchy.any { it.objectId == expectedClass.objectId }

    operator fun get(
      declaringClass: KClass<out Any>,
      fieldName: String
    ): HeapClassField? {
      return readField(declaringClass, fieldName)
    }

    operator fun get(
      declaringClassName: String,
      fieldName: String
    ) = readField(declaringClassName, fieldName)

    fun readField(
      declaringClass: KClass<out Any>,
      fieldName: String
    ): HeapClassField? {
      return readField(declaringClass.java.name, fieldName)
    }

    fun readField(
      declaringClassName: String,
      fieldName: String
    ): HeapClassField? {
      return readFields().firstOrNull { field -> field.declaringClass.name == declaringClassName && field.name == fieldName }
    }

    val instanceClassName: String
      get() = graph.className(indexedObject.classId)

    val instanceClassSimpleName: String
      get() = classSimpleName(instanceClassName)

    val instanceClass: HeapClass
      get() = graph.findObjectById(indexedObject.classId) as HeapClass

    fun readFields(): Sequence<HeapClassField> {
      val fieldReader by lazy {
        graph.createFieldValuesReader(readRecord())
      }
      return instanceClass.classHierarchy
          .map { heapClass ->
            heapClass.readRecord()
                .fields.asSequence()
                .map { fieldRecord ->
                  val fieldName = graph.fieldName(fieldRecord)
                  val fieldValue = fieldReader.readValue(fieldRecord)
                  HeapClassField(heapClass, fieldName, HeapValue(graph, fieldValue))
                }
          }
          .flatten()
    }

    fun readAsJavaString(): String? {
      if (instanceClassName != "java.lang.String") {
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
      return "instance @$objectId of $instanceClassName"
    }
  }

  /**
   * Represents an object array in the heap dump and provides navigation capabilities.
   */
  class HeapObjectArray internal constructor(
    override val graph: HeapGraph,
    private val indexedObject: IndexedObjectArray,
    override val objectId: Long,
    val isPrimitiveWrapperArray: Boolean
  ) : HeapObject() {

    val arrayClassName: String
      get() = graph.className(indexedObject.arrayClassId)

    val arrayClassSimpleName: String
      get() = classSimpleName(arrayClassName)

    val arrayClass: HeapClass
      get() = graph.findObjectById(indexedObject.arrayClassId) as HeapClass

    fun readSize(): Int {
      return readRecord().elementIds.size * graph.objectIdByteSize
    }

    override fun readRecord(): ObjectArrayDumpRecord {
      return graph.readObjectArrayDumpRecord(objectId, indexedObject)
    }

    fun readElements(): Sequence<HeapValue> {
      return readRecord().elementIds.asSequence()
          .map { HeapValue(graph, ReferenceHolder(it)) }
    }

    override fun toString(): String {
      return "object array @$objectId of $arrayClassName"
    }
  }

  /**
   * Represents a primitive array in the heap dump and provides navigation capabilities.
   */
  class HeapPrimitiveArray internal constructor(
    override val graph: HeapGraph,
    private val indexedObject: IndexedPrimitiveArray,
    override val objectId: Long
  ) : HeapObject() {

    fun readSize(): Int {
      return when (val record = readRecord()) {
        is BooleanArrayDump -> record.array.size * PrimitiveType.BOOLEAN.byteSize
        is CharArrayDump -> record.array.size * PrimitiveType.CHAR.byteSize
        is FloatArrayDump -> record.array.size * PrimitiveType.FLOAT.byteSize
        is DoubleArrayDump -> record.array.size * PrimitiveType.DOUBLE.byteSize
        is ByteArrayDump -> record.array.size * PrimitiveType.BYTE.byteSize
        is ShortArrayDump -> record.array.size * PrimitiveType.SHORT.byteSize
        is IntArrayDump -> record.array.size * PrimitiveType.INT.byteSize
        is LongArrayDump -> record.array.size * PrimitiveType.LONG.byteSize
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

  companion object {
    private fun classSimpleName(className: String): String {
      val separator = className.lastIndexOf('.')
      return if (separator == -1) {
        className
      } else {
        className.substring(separator + 1)
      }
    }
  }

}