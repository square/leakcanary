package leakcanary.internal

import leakcanary.GcRoot
import leakcanary.GcRoot.StickyClass
import leakcanary.ValueHolder
import leakcanary.ValueHolder.BooleanHolder
import leakcanary.ValueHolder.ByteHolder
import leakcanary.ValueHolder.CharHolder
import leakcanary.ValueHolder.DoubleHolder
import leakcanary.ValueHolder.FloatHolder
import leakcanary.ValueHolder.IntHolder
import leakcanary.ValueHolder.LongHolder
import leakcanary.ValueHolder.ReferenceHolder
import leakcanary.ValueHolder.ShortHolder
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
import kotlin.random.Random
import kotlin.reflect.KClass

class HprofWriterHelper constructor(
  private val writer: HprofWriter
) : Closeable {

  private var lastId = 0L
  private val id: Long
    get() = ++lastId

  private val weakRefKeyRandom = Random(42)

  // Sequence identical for every test run
  private val weakRefKey: String
    get() =
      UUID(weakRefKeyRandom.nextLong(), weakRefKeyRandom.nextLong()).toString()

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
      "value" to ReferenceHolder::class,
      "count" to IntHolder::class
  )
  )

  private val referenceClassId = clazz(
      className = "java.lang.ref.Reference",
      fields = listOf(
          "referent" to ReferenceHolder::class
      )
  )

  private val weakReferenceClassId = clazz(
      className = "java.lang.ref.WeakReference",
      superClassId = referenceClassId
  )
  private val keyedWeakReferenceClassId = clazz(
      superClassId = weakReferenceClassId,
      className = "leakcanary.KeyedWeakReference",
      staticFields = listOf("heapDumpUptimeMillis" to LongHolder(30000)),
      fields = listOf(
          "key" to ReferenceHolder::class,
          "name" to ReferenceHolder::class,
          "watchUptimeMillis" to LongHolder::class,
          "retainedUptimeMillis" to LongHolder::class
      )
  )

  fun clazz(
    className: String,
    superClassId: Long = -1L, // -1 defaults to java.lang.Object
    staticFields: List<Pair<String, ValueHolder>> = emptyList(),
    fields: List<Pair<String, KClass<out ValueHolder>>> = emptyList()
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
  ): ReferenceHolder {
    return instance(
        stringClassId,
        fields = listOf(string.charArrayDump, IntHolder(string.length))
    )
  }

  fun keyedWeakReference(
    referentInstanceId: ReferenceHolder
  ): ReferenceHolder {
    val referenceKey = string(weakRefKey)
    return instance(
        classId = keyedWeakReferenceClassId,
        fields = listOf(
            referenceKey,
            string(""),
            LongHolder(5000),
            LongHolder(20000),
            ReferenceHolder(referentInstanceId.value)
        )
    )
  }

  fun instance(
    classId: Long,
    fields: List<ValueHolder> = emptyList()
  ): ReferenceHolder {
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
    return ReferenceHolder(instanceDump.id)
  }

  inner class InstanceAndClassDefinition {
    val field = LinkedHashMap<String, ValueHolder>()
    val staticField = LinkedHashMap<String, ValueHolder>()
  }

  inner class ClassDefinition {
    val staticField = LinkedHashMap<String, ValueHolder>()
  }

  infix fun String.watchedInstance(block: InstanceAndClassDefinition.() -> Unit): ReferenceHolder {
    val instance = this.instance(block)
    keyedWeakReference(instance)
    return instance
  }

  infix fun String.instance(block: InstanceAndClassDefinition.() -> Unit): ReferenceHolder {
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

  val String.charArrayDump: ReferenceHolder
    get() {
      val arrayDump = CharArrayDump(id, 1, toCharArray())
      writer.write(arrayDump)
      return ReferenceHolder(arrayDump.id)
    }

  fun objectArray(
    vararg elements: ReferenceHolder
  ): ReferenceHolder {
    return objectArrayOf(objectArrayClassId, *elements)
  }

  fun objectArrayOf(
    classId: Long,
    vararg elements: ReferenceHolder
  ): ReferenceHolder {
    return ReferenceHolder(objectArray(classId, elements.map { it.value }.toLongArray()))
  }

  fun objectArray(
    classId: Long,
    array: LongArray
  ): Long {
    val arrayDump = ObjectArrayDumpRecord(id, 1, classId, array)
    writer.write(arrayDump)
    return arrayDump.id
  }

  private fun typeOf(wrapper: ValueHolder): Int {
    return when (wrapper) {
      is ReferenceHolder -> HprofReader.OBJECT_TYPE
      is BooleanHolder -> HprofReader.BOOLEAN_TYPE
      is CharHolder -> HprofReader.CHAR_TYPE
      is FloatHolder -> HprofReader.FLOAT_TYPE
      is DoubleHolder -> HprofReader.DOUBLE_TYPE
      is ByteHolder -> HprofReader.BYTE_TYPE
      is ShortHolder -> HprofReader.SHORT_TYPE
      is IntHolder -> HprofReader.INT_TYPE
      is LongHolder -> HprofReader.LONG_TYPE
    }
  }

  private fun typeOf(wrapperClass: KClass<out ValueHolder>): Int {
    return when (wrapperClass) {
      ReferenceHolder::class -> HprofReader.OBJECT_TYPE
      BooleanHolder::class -> HprofReader.BOOLEAN_TYPE
      CharHolder::class -> HprofReader.CHAR_TYPE
      FloatHolder::class -> HprofReader.FLOAT_TYPE
      DoubleHolder::class -> HprofReader.DOUBLE_TYPE
      ByteHolder::class -> HprofReader.BYTE_TYPE
      ShortHolder::class -> HprofReader.SHORT_TYPE
      IntHolder::class -> HprofReader.INT_TYPE
      LongHolder::class -> HprofReader.LONG_TYPE
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