package leakcanary

import leakcanary.GraphObjectRecord.GraphClassRecord
import leakcanary.GraphObjectRecord.GraphInstanceRecord
import leakcanary.GraphObjectRecord.GraphObjectArrayRecord
import leakcanary.GraphObjectRecord.GraphPrimitiveArrayRecord
import leakcanary.HprofPushRecordsParser.OnRecordListener
import leakcanary.Record.HeapDumpRecord.ObjectRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
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
import leakcanary.internal.HprofInMemoryIndex
import leakcanary.internal.IndexedObject
import leakcanary.internal.IndexedObject.IndexedClass
import leakcanary.internal.IndexedObject.IndexedInstance
import leakcanary.internal.IndexedObject.IndexedObjectArray
import leakcanary.internal.IndexedObject.IndexedPrimitiveArray
import leakcanary.internal.LruCache
import okio.Buffer
import java.io.Closeable
import java.io.File

/**
 * Enables navigation through the Hprof graph of objects.
 */
class HprofGraph internal constructor(
  private val reader: SeekableHprofReader,
  private val index: HprofInMemoryIndex
) {

  /**
   * LRU cache size of 3000 is a sweet spot to balance hits vs memory usage.
   * This is based on running InstrumentationLeakDetectorTest a bunch of time on a
   * Pixel 2 XL API 28. Hit count was ~120K, miss count ~290K
   */
  private val objectCache = LruCache<Long, ObjectRecord>(3000)

  fun indexedClass(className: String): GraphClassRecord? {
    val classId = index.classId(className)
    return if (classId == null) {
      null
    } else {
      return indexedObject(classId) as GraphClassRecord
    }
  }

  fun readObjectRecord(objectId: Long): ObjectRecord {
    return when (val indexedObject = index.indexedObject(objectId)) {
      is IndexedInstance -> readInstanceDumpRecord(objectId, indexedObject)
      is IndexedClass -> readClassDumpRecord(objectId, indexedObject)
      is IndexedObjectArray -> readObjectArrayDumpRecord(objectId, indexedObject)
      is IndexedPrimitiveArray -> readPrimitiveArrayDumpRecord(objectId, indexedObject)
    }
  }

  fun indexedObject(objectId: Long): GraphObjectRecord {
    return when (val indexedObject = index.indexedObject(objectId)) {
      is IndexedClass -> GraphClassRecord(this, indexedObject, objectId)
      is IndexedInstance -> {
        val isPrimitiveWrapper = index.primitiveWrapperTypes.contains(indexedObject.classId)
        GraphInstanceRecord(this, indexedObject, objectId, isPrimitiveWrapper)
      }
      is IndexedObjectArray -> {
        val isPrimitiveWrapperArray =
          index.primitiveWrapperTypes.contains(indexedObject.arrayClassId)
        GraphObjectArrayRecord(this, indexedObject, objectId, isPrimitiveWrapperArray)
      }
      is IndexedPrimitiveArray -> GraphPrimitiveArrayRecord(this, indexedObject, objectId)
    }
  }

  fun computeShallowSize(graphObject: GraphObjectRecord): Int {
    return when (graphObject) {
      is GraphInstanceRecord -> graphObject.instanceClass.instanceSize
      is GraphObjectArrayRecord -> graphObject.readRecord().elementIds.size * index.idSize
      is GraphPrimitiveArrayRecord -> when (val record = graphObject.readRecord()) {
        is BooleanArrayDump -> record.array.size * HprofReader.BOOLEAN_SIZE
        is CharArrayDump -> record.array.size * HprofReader.CHAR_SIZE
        is FloatArrayDump -> record.array.size * HprofReader.FLOAT_SIZE
        is DoubleArrayDump -> record.array.size * HprofReader.DOUBLE_SIZE
        is ByteArrayDump -> record.array.size * HprofReader.BYTE_SIZE
        is ShortArrayDump -> record.array.size * HprofReader.SHORT_SIZE
        is IntArrayDump -> record.array.size * HprofReader.INT_SIZE
        is LongArrayDump -> record.array.size * HprofReader.LONG_SIZE
      }
      is GraphClassRecord -> throw IllegalStateException(
          "Unexpected record ${graphObject.readRecord()}"
      )
    }
  }

  fun instanceSequence(): Sequence<GraphInstanceRecord> {
    return index.indexedInstanceSequence()
        .map {
          val objectId = it.first
          val indexedObject = it.second
          val isPrimitiveWrapper = index.primitiveWrapperTypes.contains(indexedObject.classId)
          GraphInstanceRecord(this, indexedObject, objectId, isPrimitiveWrapper)
        }
  }

  fun classSequence(): Sequence<GraphClassRecord> {
    return index.indexedClassSequence()
        .map {
          val objectId = it.first
          val indexedObject = it.second
          GraphClassRecord(this, indexedObject, objectId)
        }
  }

  fun sizeOfFieldType(hprofType: Int) = index.sizeOfFieldType(hprofType)

  internal fun fieldName(fieldRecord: FieldRecord): String {
    return index.hprofStringById(fieldRecord.nameStringId)
  }

  internal fun staticFieldName(fieldRecord: StaticFieldRecord): String {
    return index.hprofStringById(fieldRecord.nameStringId)
  }

  internal fun createFieldValuesReader(record: InstanceDumpRecord): FieldValuesReader {
    val buffer = Buffer()
    buffer.write(record.fieldValues)

    val reader = HprofReader(buffer, 0, index.idSize)

    return object : FieldValuesReader {
      override fun readValue(field: FieldRecord): HeapValue {
        return reader.readValue(field.type)
      }
    }
  }

  internal fun className(classId: Long): String {
    return index.className(classId)
  }

  internal fun readObjectArrayDumpRecord(
    objectId: Long,
    indexedObject: IndexedObjectArray
  ): ObjectArrayDumpRecord {
    return readObjectRecord(objectId, indexedObject) {
      reader.readObjectArrayDumpRecord()
    }
  }

  internal fun readPrimitiveArrayDumpRecord(
    objectId: Long,
    indexedObject: IndexedPrimitiveArray
  ): PrimitiveArrayDumpRecord {
    return readObjectRecord(objectId, indexedObject) {
      reader.readPrimitiveArrayDumpRecord()
    }
  }

  internal fun readClassDumpRecord(
    objectId: Long,
    indexedObject: IndexedClass
  ): ClassDumpRecord {
    return readObjectRecord(objectId, indexedObject) {
      reader.readClassDumpRecord()
    }
  }

  internal fun readInstanceDumpRecord(
    objectId: Long,
    indexedObject: IndexedInstance
  ): InstanceDumpRecord {
    return readObjectRecord(objectId, indexedObject) {
      reader.readInstanceDumpRecord()
    }
  }

  private fun <T : ObjectRecord> readObjectRecord(
    objectId: Long,
    indexedObject: IndexedObject,
    readBlock: () -> T
  ): T {
    val objectRecordOrNull = objectCache[objectId]
    @Suppress("UNCHECKED_CAST")
    if (objectRecordOrNull != null) {
      return objectRecordOrNull as T
    }
    reader.moveTo(indexedObject.position)
    return readBlock().apply { objectCache.put(objectId, this) }
  }

  companion object {
    fun readHprof(
      heapDump: File,
      vararg onRecordListeners: OnRecordListener
    ): Pair<HprofGraph, Closeable> {
      val indexListener = HprofInMemoryIndex.createOnRecordListener()

      val parser = HprofPushRecordsParser()

      val reader = parser.readHprofRecords(heapDump, setOf(indexListener) + onRecordListeners)

      val hprofGraph = HprofGraph(reader, indexListener.buildIndex())

      return hprofGraph to reader
    }
  }

}