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
 * An object in the heap dump.
 */
sealed class HeapObject {

  /**
   * The graph of objects in the heap, which you can use to navigate the heap.
   */

  abstract val graph: HeapGraph

  /**
   * The heap identifier of this object.
   */
  abstract val objectId: Long

  /**
   * Reads and returns the underlying [ObjectRecord].
   *
   * This may trigger IO reads.
   */
  abstract fun readRecord(): ObjectRecord

  /**
   * This [HeapObject] as a [HeapClass] if it is one, or null otherwise
   */
  val asClass: HeapClass?
    get() = if (this is HeapClass) this else null

  /**
   * This [HeapObject] as a [HeapInstance] if it is one, or null otherwise
   */
  val asInstance: HeapInstance?
    get() = if (this is HeapInstance) this else null

  /**
   * This [HeapObject] as a [HeapObjectArray] if it is one, or null otherwise
   */
  val asObjectArray: HeapObjectArray?
    get() = if (this is HeapObjectArray) this else null

  /**
   * This [HeapObject] as a [HeapPrimitiveArray] if it is one, or null otherwise
   */
  val asPrimitiveArray: HeapPrimitiveArray?
    get() = if (this is HeapPrimitiveArray) this else null

  /**
   * A class in the heap dump.
   */
  class HeapClass internal constructor(
    private val hprofGraph: HprofHeapGraph,
    private val indexedObject: IndexedClass,
    override val objectId: Long
  ) : HeapObject() {
    override val graph: HeapGraph
      get() = hprofGraph

    /**
     * The name of this class, identical to [Class.getName].
     */
    val name: String
      get() = hprofGraph.className(objectId)

    /**
     * Returns [name] stripped of any string content before the last period (included).
     */
    val simpleName: String
      get() = classSimpleName(name)

    /**
     * The total byte size of fields for instances of this class, as registered in the class dump.
     * This includes the size of fields from superclasses.
     *
     * @see readFieldsByteSize
     */
    val instanceByteSize: Int
      get() = indexedObject.instanceSize

    /**
     * The total byte size of fields for instances of this class, computed as the sum of the
     * individual size of each field of this class. This does not include the size of fields from
     * superclasses.
     *
     * This may trigger IO reads.
     *
     * @see instanceByteSize
     */
    fun readFieldsByteSize(): Int {
      return readRecord()
          .fields.sumBy {
        if (it.type == PrimitiveType.REFERENCE_HPROF_TYPE) {
          hprofGraph.identifierByteSize
        } else PrimitiveType.byteSizeByHprofType.getValue(it.type)
      }
    }

    /**
     * The [HeapClass] representing the superclass of this [HeapClass]. If this [HeapClass]
     * represents either the [Object] class or a primitive type, then
     * null is returned. If this [HeapClass] represents an array class then the
     * [HeapClass] object representing the [Object] class is returned.
     */
    val superclass: HeapClass?
      get() {
        if (indexedObject.superclassId == ValueHolder.NULL_REFERENCE) return null
        return hprofGraph.findObjectById(indexedObject.superclassId) as HeapClass
      }

    /**
     * The class hierarchy starting at this class (included) and ending at the [Object] class
     * (included).
     */
    val classHierarchy: Sequence<HeapClass>
      get() = generateSequence(this) { it.superclass }

    /**
     * All the subclasses (direct and indirect) of this class,
     * in the order they were recorded in the heap dump.
     */
    val subclasses: Sequence<HeapClass>
      get() = hprofGraph.classes.filter { it subclassOf this }

    /**
     * Returns true if [subclass] is a sub class of this [HeapClass].
     */
    infix fun superclassOf(subclass: HeapClass): Boolean {
      return subclass.classHierarchy.any { it.objectId == objectId }
    }

    /**
     * Returns true if [superclass] is a superclass of this [HeapClass].
     */
    infix fun subclassOf(superclass: HeapClass): Boolean {
      return classHierarchy.any { it.objectId == superclass.objectId }
    }

    /**
     * All instances of this class, including instances of subclasses of this class.
     */
    val instances: Sequence<HeapInstance>
      get() = hprofGraph.instances.filter { it instanceOf this }

    /**
     * All direct instances of this class, ie excluding any instance of subclasses of this class.
     */
    val directInstances: Sequence<HeapInstance>
      get() = hprofGraph.instances.filter { it.indexedObject.classId == objectId }

    /**
     * Reads and returns the underlying [ClassDumpRecord].
     *
     * This may trigger IO reads.
     */
    override fun readRecord(): ClassDumpRecord {
      return hprofGraph.readClassDumpRecord(objectId, indexedObject)
    }

    /**
     * The static fields of this class, as a sequence of [HeapField].
     *
     * This may trigger IO reads.
     */
    fun readStaticFields(): Sequence<HeapField> {
      return readRecord().staticFields.asSequence()
          .map { fieldRecord ->
            HeapField(
                this, hprofGraph.staticFieldName(fieldRecord),
                HeapValue(hprofGraph, fieldRecord.value)
            )
          }
    }

    /**
     * Returns a [HeapField] object that reflects the specified declared
     * field of the class represented by this [HeapClass] object, or null if this field does not
     * exist. The [name] parameter specifies the simple name of the desired field.
     *
     * Also available as a convenience operator: [get]
     *
     * This may trigger IO reads.
     */
    fun readStaticField(fieldName: String): HeapField? {
      for (fieldRecord in readRecord().staticFields) {
        if (hprofGraph.staticFieldName(fieldRecord) == fieldName) {
          return HeapField(
              this, hprofGraph.staticFieldName(fieldRecord),
              HeapValue(hprofGraph, fieldRecord.value)
          )
        }
      }
      return null
    }

    /**
     * @see readStaticField
     */
    operator fun get(fieldName: String) = readStaticField(fieldName)

    override fun toString(): String {
      return "class $name"
    }
  }

  /**
   * An instance in the heap dump.
   */
  class HeapInstance internal constructor(
    private val hprofGraph: HprofHeapGraph,
    internal val indexedObject: IndexedInstance,
    override val objectId: Long,
    /**
     * Whether this is an instance of a primitive wrapper type.
     */
    val isPrimitiveWrapper: Boolean
  ) : HeapObject() {

    override val graph: HeapGraph
      get() = hprofGraph

    /**
     * @see HeapClass.instanceByteSize
     */
    val byteSize
      get() = instanceClass.instanceByteSize

    /**
     * The name of the class of this instance, identical to [Class.getName].
     */
    val instanceClassName: String
      get() = hprofGraph.className(indexedObject.classId)

    /**
     * Returns [instanceClassName] stripped of any string content before the last period (included).
     */
    val instanceClassSimpleName: String
      get() = classSimpleName(instanceClassName)

    /**
     * The class of this instance.
     */
    val instanceClass: HeapClass
      get() = hprofGraph.findObjectById(indexedObject.classId) as HeapClass

    /**
     * Reads and returns the underlying [InstanceDumpRecord].
     *
     * This may trigger IO reads.
     */
    override fun readRecord(): InstanceDumpRecord {
      return hprofGraph.readInstanceDumpRecord(objectId, indexedObject)
    }

    /**
     * Returns true if this is an instance of the class named [className] or an instance of a
     * subclass of that class.
     */
    infix fun instanceOf(className: String): Boolean =
      instanceClass.classHierarchy.any { it.name == className }

    /**
     * Returns true if this is an instance of [expectedClass] or an instance of a subclass of that
     * class.
     */
    infix fun instanceOf(expectedClass: KClass<*>) =
      this instanceOf expectedClass.java.name

    /**
     * Returns true if this is an instance of [expectedClass] or an instance of a subclass of that
     * class.
     */
    infix fun instanceOf(expectedClass: HeapClass) =
      instanceClass.classHierarchy.any { it.objectId == expectedClass.objectId }

    /**
     * @see readField
     */
    fun readField(
      declaringClass: KClass<out Any>,
      fieldName: String
    ): HeapField? {
      return readField(declaringClass.java.name, fieldName)
    }

    /**
     * Returns a [HeapField] object that reflects the specified declared
     * field of the instance represented by this [HeapInstance] object, or null if this field does
     * not exist. The [declaringClassName] specifies the class in which the desired field is
     * declared, and the [fieldName] parameter specifies the simple name of the desired field.
     *
     * Also available as a convenience operator: [get]
     *
     * This may trigger IO reads.
     */
    fun readField(
      declaringClassName: String,
      fieldName: String
    ): HeapField? {
      return readFields().firstOrNull { field -> field.declaringClass.name == declaringClassName && field.name == fieldName }
    }

    /**
     * @see readField
     */
    operator fun get(
      declaringClass: KClass<out Any>,
      fieldName: String
    ): HeapField? {
      return readField(declaringClass, fieldName)
    }

    /**
     * @see readField
     */
    operator fun get(
      declaringClassName: String,
      fieldName: String
    ) = readField(declaringClassName, fieldName)

    /**
     * The fields of this instance, as a sequence of [HeapField].
     *
     * This may trigger IO reads.
     */
    fun readFields(): Sequence<HeapField> {
      val fieldReader by lazy {
        hprofGraph.createFieldValuesReader(readRecord())
      }
      return instanceClass.classHierarchy
          .map { heapClass ->
            heapClass.readRecord()
                .fields.asSequence()
                .map { fieldRecord ->
                  val fieldName = hprofGraph.fieldName(fieldRecord)
                  val fieldValue = fieldReader.readValue(fieldRecord)
                  HeapField(heapClass, fieldName, HeapValue(hprofGraph, fieldValue))
                }
          }
          .flatten()
    }

    /**
     * If this [HeapInstance] is an instance of the [String] class, returns a [String] instance
     * with content that matches the string in the heap dump. Otherwise returns null.
     *
     * This may trigger IO reads.
     */
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
   * An object array in the heap dump.
   */
  class HeapObjectArray internal constructor(
    private val hprofGraph: HprofHeapGraph,
    private val indexedObject: IndexedObjectArray,
    override val objectId: Long,
    val isPrimitiveWrapperArray: Boolean
  ) : HeapObject() {

    override val graph: HeapGraph
      get() = hprofGraph

    /**
     * The name of the class of this array, identical to [Class.getName].
     */
    val arrayClassName: String
      get() = hprofGraph.className(indexedObject.arrayClassId)

    /**
     * Returns [arrayClassName] stripped of any string content before the last period (included).
     */
    val arrayClassSimpleName: String
      get() = classSimpleName(arrayClassName)

    /**
     * The class of this array.
     */
    val arrayClass: HeapClass
      get() = hprofGraph.findObjectById(indexedObject.arrayClassId) as HeapClass

    /**
     * The total byte shallow size of elements in this array.
     */
    fun readByteSize(): Int {
      return readRecord().elementIds.size * hprofGraph.identifierByteSize
    }

    /**
     * Reads and returns the underlying [ObjectArrayDumpRecord].
     *
     * This may trigger IO reads.
     */
    override fun readRecord(): ObjectArrayDumpRecord {
      return hprofGraph.readObjectArrayDumpRecord(objectId, indexedObject)
    }

    /**
     * The elements in this array, as a sequence of [HeapValue].
     *
     * This may trigger IO reads.
     */
    fun readElements(): Sequence<HeapValue> {
      return readRecord().elementIds.asSequence()
          .map { HeapValue(hprofGraph, ReferenceHolder(it)) }
    }

    override fun toString(): String {
      return "object array @$objectId of $arrayClassName"
    }
  }

  /**
   * A primitive array in the heap dump.
   */
  class HeapPrimitiveArray internal constructor(
    private val hprofGraph: HprofHeapGraph,
    private val indexedObject: IndexedPrimitiveArray,
    override val objectId: Long
  ) : HeapObject() {

    override val graph: HeapGraph
      get() = hprofGraph

    /**
     * The total byte shallow size of elements in this array.
     */
    fun readByteSize(): Int {
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

    /**
     * The [PrimitiveType] of elements in this array.
     */
    val primitiveType: PrimitiveType
      get() = indexedObject.primitiveType

    /**
     * The name of the class of this array, identical to [Class.getName].
     */
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

    /**
     * Reads and returns the underlying [PrimitiveArrayDumpRecord].
     *
     * This may trigger IO reads.
     */
    override fun readRecord(): PrimitiveArrayDumpRecord {
      return hprofGraph.readPrimitiveArrayDumpRecord(objectId, indexedObject)
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