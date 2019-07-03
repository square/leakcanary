package leakcanary.internal

import leakcanary.GcRoot
import leakcanary.GcRoot.StickyClass
import leakcanary.HeapValue
import leakcanary.HeapValue.BooleanValue
import leakcanary.HeapValue.ByteValue
import leakcanary.HeapValue.CharValue
import leakcanary.HeapValue.DoubleValue
import leakcanary.HeapValue.FloatValue
import leakcanary.HeapValue.IntValue
import leakcanary.HeapValue.LongValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HeapValue.ShortValue
import leakcanary.HprofReader
import leakcanary.HprofWriter
import leakcanary.Record.HeapDumpRecord.GcRootRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import leakcanary.Record.LoadClassRecord
import leakcanary.Record.StringRecord
import okio.Buffer
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlin.reflect.KClass

class HprofWriterHelper constructor(
  private val writer: HprofWriter
) : Closeable {

  private var lastId = 0L
  private val id: Long
    get() = ++lastId

  private val typeSizes = mapOf(
      // object
      HprofReader.OBJECT_TYPE to writer.idSize,
      HprofReader.BOOLEAN_TYPE to HprofReader.BOOLEAN_SIZE,
      HprofReader.CHAR_TYPE to HprofReader.CHAR_SIZE,
      HprofReader.FLOAT_TYPE to HprofReader.FLOAT_SIZE,
      HprofReader.DOUBLE_TYPE to HprofReader.DOUBLE_SIZE,
      HprofReader.BYTE_TYPE to HprofReader.BYTE_SIZE,
      HprofReader.SHORT_TYPE to HprofReader.SHORT_SIZE,
      HprofReader.INT_TYPE to HprofReader.INT_SIZE,
      HprofReader.LONG_TYPE to HprofReader.LONG_SIZE
  )

  private val classDumps = mutableMapOf<Long, ClassDumpRecord>()

  private val objectClassId = clazz(superClassId = 0, className = "java.lang.Object")
  private val objectArrayClassId = arrayClass("java.lang.Object")
  private val stringClassId = clazz(
      className = "java.lang.String", fields = listOf(
      "value" to ObjectReference::class,
      "count" to IntValue::class
  )
  )

  private val referenceClassId  = clazz(
      className = "java.lang.ref.Reference",
      fields = listOf(
          "referent" to ObjectReference::class
      )
  )

  private val weakReferenceClassId = clazz(
      className = "java.lang.ref.WeakReference",
      superClassId = referenceClassId
  )
  private val keyedWeakReferenceClassId = clazz(
      superClassId = weakReferenceClassId,
      className = "leakcanary.KeyedWeakReference",
      staticFields = listOf("heapDumpUptimeMillis" to LongValue(Long.MAX_VALUE)),
      fields = listOf(
          "key" to ObjectReference::class,
          "name" to ObjectReference::class,
          "className" to ObjectReference::class,
          "watchUptimeMillis" to LongValue::class,
          "retainedUptimeMillis" to LongValue::class
      )
  )

  fun clazz(
    className: String,
    superClassId: Long = -1L, // -1 defaults to java.lang.Object
    staticFields: List<Pair<String, HeapValue>> = emptyList(),
    fields: List<Pair<String, KClass<out HeapValue>>> = emptyList()
  ): Long {
    val classNameRecord = StringRecord(id, className)
    writer.write(classNameRecord)
    val loadClass = LoadClassRecord(1, id, 1, classNameRecord.id)
    writer.write(loadClass)

    val staticFieldRecords = staticFields.map {
      val fieldName = StringRecord(id, it.first)
      writer.write(fieldName)
      StaticFieldRecord(fieldName.id, typeOf(it.second), it.second)
    }

    val fieldRecords = fields.map {
      val fieldName = StringRecord(id, it.first)
      writer.write(fieldName)
      FieldRecord(fieldName.id, typeOf(it.second))
    }

    var instanceSize = fieldRecords.sumBy {
      typeSizes.getValue(it.type)
    }

    var nextUpId = if (superClassId == -1L) objectClassId else superClassId
    while (nextUpId != 0L) {
      val nextUp = classDumps[nextUpId]!!
      instanceSize += nextUp.fields.sumBy {
        typeSizes.getValue(it.type)
      }
      nextUpId = nextUp.superClassId
    }
    val classDump = ClassDumpRecord(
        id = loadClass.id,
        stackTraceSerialNumber = 1,
        superClassId = if (superClassId == -1L) objectClassId else superClassId,
        classLoaderId = 0,
        signersId = 0,
        protectionDomainId = 0,
        instanceSize = instanceSize,
        staticFields = staticFieldRecords,
        fields = fieldRecords
    )
    classDumps[loadClass.id] = classDump
    writer.write(classDump)
    val gcRoot = StickyClass(classDump.id)
    gcRoot(gcRoot)
    return classDump.id
  }

  fun gcRoot(gcRoot: GcRoot) {
    val gcRootRecord = GcRootRecord(gcRoot = gcRoot)
    writer.write(gcRootRecord)
  }

  fun arrayClass(className: String): Long {
    return clazz(className = "$className[]")
  }

  fun string(
    string: String
  ): ObjectReference {
    return instance(
        stringClassId,
        fields = listOf(string.charArrayDump, IntValue(string.length))
    )
  }

  fun keyedWeakReference(
    className: String,
    referentInstanceId: ObjectReference
  ): ObjectReference {
    val referenceKey = string(UUID.randomUUID().toString())
    return instance(
        classId = keyedWeakReferenceClassId,
        fields = listOf(
            referenceKey,
            string(""),
            string(className),
            LongValue(System.currentTimeMillis()),
            LongValue(System.currentTimeMillis()),
            ObjectReference(referentInstanceId.value)
        )
    )
  }

  fun instance(
    classId: Long,
    fields: List<HeapValue> = emptyList()
  ): ObjectReference {
    val fieldsBuffer = Buffer()
    fields.forEach { value ->
      with(writer) {
        fieldsBuffer.writeValue(value)
      }
    }
    val instanceDump = InstanceDumpRecord(
        id = id,
        stackTraceSerialNumber = 1,
        classId = classId,
        fieldValues = fieldsBuffer.readByteArray()
    )
    writer.write(instanceDump)
    return ObjectReference(instanceDump.id)
  }

  inner class InstanceAndClassDefinition {
    val field = LinkedHashMap<String, HeapValue>()
    val staticField = LinkedHashMap<String, HeapValue>()
  }

  inner class ClassDefinition {
    val staticField = LinkedHashMap<String, HeapValue>()
  }

  infix fun String.watchedInstance(block: InstanceAndClassDefinition.() -> Unit): ObjectReference {
    val instance = this.instance(block)
    keyedWeakReference("DummyClassName", instance)
    return instance
  }

  infix fun String.instance(block: InstanceAndClassDefinition.() -> Unit): ObjectReference {
    val definition = InstanceAndClassDefinition()
    block(definition)

    val classFields = definition.field.map {
      it.key to it.value::class
    }

    val staticFields = definition.staticField.map { it.key to it.value }

    val instanceFields = definition.field.map { it.value }

    return instance(clazz(this, fields = classFields, staticFields = staticFields), instanceFields)
  }

  infix fun String.clazz(block: ClassDefinition.() -> Unit): Long {
    val definition = ClassDefinition()
    block(definition)

    val staticFields = definition.staticField.map { it.key to it.value }
    return clazz(this, staticFields = staticFields)
  }

  val String.charArrayDump: ObjectReference
    get() {
      val arrayDump = CharArrayDump(id, 1, toCharArray())
      writer.write(arrayDump)
      return ObjectReference(arrayDump.id)
    }

  fun objectArray(
    vararg elements: ObjectReference
  ): ObjectReference {
    return objectArrayOf(objectArrayClassId, *elements)
  }

  fun objectArrayOf(
    classId: Long,
    vararg elements: ObjectReference
  ): ObjectReference {
    return ObjectReference(objectArray(classId, elements.map { it.value }.toLongArray()))
  }

  fun objectArray(
    classId: Long,
    array: LongArray
  ): Long {
    val arrayDump = ObjectArrayDumpRecord(id, 1, classId, array)
    writer.write(arrayDump)
    return arrayDump.id
  }

  private fun typeOf(wrapper: HeapValue): Int {
    return when (wrapper) {
      is ObjectReference -> HprofReader.OBJECT_TYPE
      is BooleanValue -> HprofReader.BOOLEAN_TYPE
      is CharValue -> HprofReader.CHAR_TYPE
      is FloatValue -> HprofReader.FLOAT_TYPE
      is DoubleValue -> HprofReader.DOUBLE_TYPE
      is ByteValue -> HprofReader.BYTE_TYPE
      is ShortValue -> HprofReader.SHORT_TYPE
      is IntValue -> HprofReader.INT_TYPE
      is LongValue -> HprofReader.LONG_TYPE
    }
  }

  private fun typeOf(wrapperClass: KClass<out HeapValue>): Int {
    return when (wrapperClass) {
      ObjectReference::class -> HprofReader.OBJECT_TYPE
      BooleanValue::class -> HprofReader.BOOLEAN_TYPE
      CharValue::class -> HprofReader.CHAR_TYPE
      FloatValue::class -> HprofReader.FLOAT_TYPE
      DoubleValue::class -> HprofReader.DOUBLE_TYPE
      ByteValue::class -> HprofReader.BYTE_TYPE
      ShortValue::class -> HprofReader.SHORT_TYPE
      IntValue::class -> HprofReader.INT_TYPE
      LongValue::class -> HprofReader.LONG_TYPE
      else -> throw IllegalArgumentException("Unexpected class $wrapperClass")
    }
  }

  override fun close() {
    writer.close()
  }
}

fun File.dump(block: HprofWriterHelper.() -> Unit) {
  HprofWriterHelper(HprofWriter.open(this)).use(block)
}

fun HprofWriter.helper(block: HprofWriterHelper.() -> Unit) {
  HprofWriterHelper(this).use(block)
}