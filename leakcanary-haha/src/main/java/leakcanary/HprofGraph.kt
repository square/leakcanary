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
import leakcanary.internal.FieldValuesReader
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
   * In memory store that can be used to store objects this [HprofGraph] instance.
   */
  val context = GraphContext()

  /**
   * All GC roots which type matches the set passed to [HprofInMemoryIndex.createOnRecordListener].
   */
  val gcRoots: List<GcRoot>
    get() = index.gcRoots()

  /**
   * Sequence of all objects in the heap dump.
   *
   * This sequence does not trigger any IO reads.
   */
  val objects: Sequence<GraphObjectRecord>
    get() {
      return index.indexedObjectSequence()
          .map {
            wrapIndexedObject(it.second, it.first)
          }
    }

  /**
   * Sequence of all classes in the heap dump.
   *
   * This sequence does not trigger any IO reads.
   */
  val classes: Sequence<GraphClassRecord>
    get() {
      return index.indexedClassSequence()
          .map {
            val objectId = it.first
            val indexedObject = it.second
            GraphClassRecord(this, indexedObject, objectId)
          }
    }

  /**
   * Sequence of all instances in the heap dump.
   *
   * This sequence does not trigger any IO reads.
   */
  val instances: Sequence<GraphInstanceRecord>
    get() {
      return index.indexedInstanceSequence()
          .map {
            val objectId = it.first
            val indexedObject = it.second
            val isPrimitiveWrapper = index.primitiveWrapperTypes.contains(indexedObject.classId)
            GraphInstanceRecord(this, indexedObject, objectId, isPrimitiveWrapper)
          }
    }

  internal val idSize
    get() = index.idSize

  // LRU cache size of 3000 is a sweet spot to balance hits vs memory usage.
  // This is based on running InstrumentationLeakDetectorTest a bunch of time on a
  // Pixel 2 XL API 28. Hit count was ~120K, miss count ~290K
  private val objectCache = LruCache<Long, ObjectRecord>(3000)

  /**
   * Returns the [GraphObjectRecord] corresponding to the provided [objectId], and throws
   * [IllegalArgumentException] otherwise.
   */
  fun findObjectByObjectId(objectId: Long): GraphObjectRecord {
    return wrapIndexedObject(index.indexedObject(objectId), objectId)
  }

  /**
   * Returns the [GraphClassRecord] corresponding to the provided [className], or null if the
   * class cannot be found.
   */
  fun findClassByClassName(className: String): GraphClassRecord? {
    val classId = index.classId(className)
    return if (classId == null) {
      null
    } else {
      return findObjectByObjectId(classId) as GraphClassRecord
    }
  }

  /**
   * Returns true if the provided [objectId] exists in the heap dump.
   */
  fun objectExists(objectId: Long): Boolean {
    return index.objectIdIsIndexed(objectId)
  }

  /**
   * Returns the byte size of the provided [hprofType].
   *
   * Note: this API may be removed eventually.
   */
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

  internal fun readObjectRecord(objectId: Long): ObjectRecord {
    return when (val indexedObject = index.indexedObject(objectId)) {
      is IndexedInstance -> readInstanceDumpRecord(objectId, indexedObject)
      is IndexedClass -> readClassDumpRecord(objectId, indexedObject)
      is IndexedObjectArray -> readObjectArrayDumpRecord(objectId, indexedObject)
      is IndexedPrimitiveArray -> readPrimitiveArrayDumpRecord(objectId, indexedObject)
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

  private fun wrapIndexedObject(
    indexedObject: IndexedObject,
    objectId: Long
  ): GraphObjectRecord {
    return when (indexedObject) {
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