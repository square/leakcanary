package shark

import okio.Buffer
import shark.GcRoot.StickyClass
import shark.HprofRecord.HeapDumpRecord.GcRootRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StringRecord
import shark.PrimitiveType.BOOLEAN
import shark.PrimitiveType.BYTE
import shark.PrimitiveType.CHAR
import shark.PrimitiveType.DOUBLE
import shark.PrimitiveType.FLOAT
import shark.PrimitiveType.INT
import shark.PrimitiveType.LONG
import shark.PrimitiveType.SHORT
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.ByteHolder
import shark.ValueHolder.CharHolder
import shark.ValueHolder.DoubleHolder
import shark.ValueHolder.FloatHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder
import shark.ValueHolder.ShortHolder
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

  private val typeSizes =
    PrimitiveType.byteSizeByHprofType + (PrimitiveType.REFERENCE_HPROF_TYPE to writer.hprofHeader.identifierByteSize)

  private val classDumps = mutableMapOf<Long, ClassDumpRecord>()

  private val objectClassId = clazz(superclassId = 0, className = "java.lang.Object")
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
      superclassId = referenceClassId
  )
  private val keyedWeakReferenceClassId = clazz(
      superclassId = weakReferenceClassId,
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
    superclassId: Long = -1L, // -1 defaults to java.lang.Object
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

    var nextUpId = if (superclassId == -1L) objectClassId else superclassId
    while (nextUpId != 0L) {
      val nextUp = classDumps[nextUpId]!!
      instanceSize += nextUp.fields.sumBy {
        typeSizes.getValue(it.type)
      }
      nextUpId = nextUp.superclassId
    }
    val classDump = ClassDumpRecord(
        id = loadClass.id,
        stackTraceSerialNumber = 1,
        superclassId = if (superclassId == -1L) objectClassId else superclassId,
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

  fun stringRecord(
    name: String
  ): StringRecord {
    val stringRecord = StringRecord(id, name)
    writer.write(stringRecord)
    return stringRecord
  }

  fun clazz(
    classNameRecord: StringRecord,
    superclassId: Long = -1L, // -1 defaults to java.lang.Object
    staticFields: List<Pair<Long, ValueHolder>> = emptyList(),
    fields: List<Pair<Long, KClass<out ValueHolder>>> = emptyList()
  ): Long {
    val loadClass = LoadClassRecord(1, id, 1, classNameRecord.id)
    writer.write(loadClass)

    val staticFieldRecords = staticFields.map {
      StaticFieldRecord(it.first, typeOf(it.second), it.second)
    }

    val fieldRecords = fields.map {
      FieldRecord(it.first, typeOf(it.second))
    }

    var instanceSize = fieldRecords.sumBy {
      typeSizes.getValue(it.type)
    }

    var nextUpId = if (superclassId == -1L) objectClassId else superclassId
    while (nextUpId != 0L) {
      val nextUp = classDumps[nextUpId]!!
      instanceSize += nextUp.fields.sumBy {
        typeSizes.getValue(it.type)
      }
      nextUpId = nextUp.superclassId
    }
    val classDump = ClassDumpRecord(
        id = loadClass.id,
        stackTraceSerialNumber = 1,
        superclassId = if (superclassId == -1L) objectClassId else superclassId,
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
            string("its lifecycle has ended"),
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
    val instanceDump = InstanceDumpRecord(
        id = id,
        stackTraceSerialNumber = 1,
        classId = classId,
        fieldValues = writer.valuesToBytes(fields)
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
      is ReferenceHolder -> PrimitiveType.REFERENCE_HPROF_TYPE
      is BooleanHolder -> BOOLEAN.hprofType
      is CharHolder -> CHAR.hprofType
      is FloatHolder -> FLOAT.hprofType
      is DoubleHolder -> DOUBLE.hprofType
      is ByteHolder -> BYTE.hprofType
      is ShortHolder -> SHORT.hprofType
      is IntHolder -> INT.hprofType
      is LongHolder -> LONG.hprofType
    }
  }

  private fun typeOf(wrapperClass: KClass<out ValueHolder>): Int {
    return when (wrapperClass) {
      ReferenceHolder::class -> PrimitiveType.REFERENCE_HPROF_TYPE
      BooleanHolder::class -> BOOLEAN.hprofType
      CharHolder::class -> CHAR.hprofType
      FloatHolder::class -> FLOAT.hprofType
      DoubleHolder::class -> DOUBLE.hprofType
      ByteHolder::class -> BYTE.hprofType
      ShortHolder::class -> SHORT.hprofType
      IntHolder::class -> INT.hprofType
      LongHolder::class -> LONG.hprofType
      else -> throw IllegalArgumentException("Unexpected class $wrapperClass")
    }
  }

  override fun close() {
    writer.close()
  }
}

fun File.dump(block: HprofWriterHelper.() -> Unit) {
  HprofWriterHelper(HprofWriter.openWriterFor(this))
      .use(block)
}

fun dump(
  hprofHeader: HprofHeader = HprofHeader(),
  block: HprofWriterHelper.() -> Unit
): DualSourceProvider {
  val buffer = Buffer()
  HprofWriterHelper(HprofWriter.openWriterFor(buffer))
      .use(block)
  return ByteArraySourceProvider(buffer.readByteArray())
}

fun List<HprofRecord>.asHprofBytes(): DualSourceProvider {
  val buffer = Buffer()
  HprofWriter.openWriterFor(buffer)
      .use { writer ->
        forEach { record ->
          writer.write(record)
        }
      }
  return ByteArraySourceProvider(buffer.readByteArray())
}
