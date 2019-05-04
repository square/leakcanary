package leakcanary.internal

import leakcanary.GcRoot.StickyClass
import leakcanary.HeapDumpMemoryStore
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
import java.util.UUID
import kotlin.reflect.KClass

class HprofWriterHelper constructor(
  private val writer: HprofWriter
) : Closeable {

  private var lastId = 0L
  private val id: Long
    get() = ++lastId

  private val weakRefKeys = mutableSetOf<Long>()

  private val objectClassId = clazz(superClassId = 0, className = "java.lang.Object")
  private val stringClassId = clazz(
      className = "java.lang.String", fields = listOf(
      "value" to ObjectReference::class,
      "count" to IntValue::class
  )
  )
  private val weakReferenceClassId = clazz(
      className = "java.lang.ref.WeakReference",
      fields = listOf(
          "referent" to ObjectReference::class
      )
  )
  private val keyedWeakReferenceClassId = clazz(
      superClassId = weakReferenceClassId,
      className = "leakcanary.KeyedWeakReference",
      fields = listOf(
          "key" to ObjectReference::class,
          "name" to ObjectReference::class,
          "className" to ObjectReference::class,
          "watchUptimeMillis" to LongValue::class
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
    val classDump = ClassDumpRecord(
        id = loadClass.id,
        stackTraceSerialNumber = 1,
        superClassId = if (superClassId == -1L) objectClassId else superClassId,
        classLoaderId = 0,
        signersId = 0,
        protectionDomainId = 0,
        instanceSize = 0,
        staticFields = staticFieldRecords,
        fields = fieldRecords
    )
    writer.write(classDump)
    val gcRootRecord = GcRootRecord(gcRoot = StickyClass(classDump.id))
    writer.write(gcRootRecord)
    return classDump.id
  }

  fun arrayClass(className: String): Long {
    return clazz(className = "$className[]")
  }

  fun string(
    string: String
  ): Long {
    return instance(
        stringClassId,
        fields = listOf(ObjectReference(array(string.toCharArray())), IntValue(string.length))
    )
  }

  fun keyedWeakReference(
    className: String,
    referentInstanceId: Long
  ): Long {
    val referenceKey = string(UUID.randomUUID().toString())
    weakRefKeys.add(referenceKey)
    return instance(
        classId = keyedWeakReferenceClassId,
        fields = listOf(
            ObjectReference(referenceKey),
            ObjectReference(string("")),
            ObjectReference(string(className)),
            LongValue(System.currentTimeMillis()),
            ObjectReference(referentInstanceId)

        )
    )
  }

  fun instance(
    classId: Long,
    fields: List<HeapValue> = emptyList()
  ): Long {
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
    return instanceDump.id
  }

  fun array(array: CharArray): Long {
    val arrayDump = CharArrayDump(id, 1, array)
    writer.write(arrayDump)
    return arrayDump.id
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
    val elementIds = weakRefKeys.toLongArray()
    val weakRefKeysArray = ObjectArrayDumpRecord(id, 1, stringClassId, elementIds)
    writer.write(weakRefKeysArray)
    clazz(
        className = HeapDumpMemoryStore::class.java.name,
        staticFields = listOf(
            "retainedKeysForHeapDump" to ObjectReference(weakRefKeysArray.id),
            "heapDumpUptimeMillis" to LongValue(System.currentTimeMillis())
        )
    )
    writer.close()
  }
}

fun HprofWriter.helper(block: HprofWriterHelper.() -> Unit) {
  HprofWriterHelper(this).use(block)
}