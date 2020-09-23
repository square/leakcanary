package shark

import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofRecord.HeapDumpRecord.ObjectRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
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
import shark.internal.FieldValuesReader
import shark.internal.HprofInMemoryIndex
import shark.internal.IndexedObject
import shark.internal.IndexedObject.IndexedClass
import shark.internal.IndexedObject.IndexedInstance
import shark.internal.IndexedObject.IndexedObjectArray
import shark.internal.IndexedObject.IndexedPrimitiveArray
import shark.internal.LruCache
import java.io.File
import kotlin.reflect.KClass

/**
 * A [HeapGraph] that reads from an Hprof file indexed by [HprofIndex].
 */
class HprofHeapGraph internal constructor(
  private val header: HprofHeader,
  private val reader: RandomAccessHprofReader,
  private val index: HprofInMemoryIndex
) : CloseableHeapGraph {

  override val identifierByteSize: Int get() = header.identifierByteSize

  override val context = GraphContext()

  override val objectCount: Int
    get() = classCount + instanceCount + objectArrayCount + primitiveArrayCount

  override val classCount: Int
    get() = index.classCount

  override val instanceCount: Int
    get() = index.instanceCount

  override val objectArrayCount: Int
    get() = index.objectArrayCount

  override val primitiveArrayCount: Int
    get() = index.primitiveArrayCount

  override val gcRoots: List<GcRoot>
    get() = index.gcRoots()

  override val objects: Sequence<HeapObject>
    get() {
      return index.indexedObjectSequence()
          .map {
            wrapIndexedObject(it.second, it.first)
          }
    }

  override val classes: Sequence<HeapClass>
    get() {
      return index.indexedClassSequence()
          .map {
            val objectId = it.first
            val indexedObject = it.second
            HeapClass(this, indexedObject, objectId)
          }
    }

  override val instances: Sequence<HeapInstance>
    get() {
      return index.indexedInstanceSequence()
          .map {
            val objectId = it.first
            val indexedObject = it.second
            val isPrimitiveWrapper = index.primitiveWrapperTypes.contains(indexedObject.classId)
            HeapInstance(this, indexedObject, objectId, isPrimitiveWrapper)
          }
    }

  override val objectArrays: Sequence<HeapObjectArray>
    get() = index.indexedObjectArraySequence().map {
      val objectId = it.first
      val indexedObject = it.second
      val isPrimitiveWrapper = index.primitiveWrapperTypes.contains(indexedObject.arrayClassId)
      HeapObjectArray(this, indexedObject, objectId, isPrimitiveWrapper)
    }

  override val primitiveArrays: Sequence<HeapPrimitiveArray>
    get() = index.indexedPrimitiveArraySequence().map {
      val objectId = it.first
      val indexedObject = it.second
      HeapPrimitiveArray(this, indexedObject, objectId)
    }

  private val objectCache = LruCache<Long, ObjectRecord>(INTERNAL_LRU_CACHE_SIZE)

  // java.lang.Object is the most accessed class in Heap, so we want to memoize a reference to it
  private val javaLangObjectClass: HeapClass? = findClassByName("java.lang.Object")

  override fun findObjectById(objectId: Long): HeapObject {
    return findObjectByIdOrNull(objectId) ?: throw IllegalArgumentException(
        "Object id $objectId not found in heap dump."
    )
  }

  override fun findObjectByIdOrNull(objectId: Long): HeapObject? {
    if (objectId == javaLangObjectClass?.objectId) return javaLangObjectClass

    val indexedObject = index.indexedObjectOrNull(objectId) ?: return null
    return wrapIndexedObject(indexedObject, objectId)
  }

  override fun findClassByName(className: String): HeapClass? {
    val classId = index.classId(className)
    return if (classId == null) {
      null
    } else {
      return findObjectById(classId) as HeapClass
    }
  }

  override fun objectExists(objectId: Long): Boolean {
    return index.objectIdIsIndexed(objectId)
  }

  override fun close() {
    reader.close()
  }

  internal fun fieldName(
    classId: Long,
    fieldRecord: FieldRecord
  ): String {
    return index.fieldName(classId, fieldRecord.nameStringId)
  }

  internal fun staticFieldName(
    classId: Long,
    fieldRecord: StaticFieldRecord
  ): String {
    return index.fieldName(classId, fieldRecord.nameStringId)
  }

  internal fun createFieldValuesReader(record: InstanceDumpRecord) =
    FieldValuesReader(record, identifierByteSize)

  internal fun className(classId: Long): String {
    return index.className(classId)
  }

  internal fun readObjectArrayDumpRecord(
    objectId: Long,
    indexedObject: IndexedObjectArray
  ): ObjectArrayDumpRecord {
    return readObjectRecord(objectId, indexedObject) {
      readObjectArrayDumpRecord()
    }
  }

  internal fun readObjectArrayByteSize(
    objectId: Long,
    indexedObject: IndexedObjectArray
  ): Int {
    val cachedRecord = objectCache[objectId] as ObjectArrayDumpRecord?
    if (cachedRecord != null) {
      return cachedRecord.elementIds.size * identifierByteSize
    }
    val position = indexedObject.position + identifierByteSize + PrimitiveType.INT.byteSize
    val size = PrimitiveType.INT.byteSize.toLong()
    val thinRecordSize = reader.readRecord(position, size) {
      readInt()
    }
    return thinRecordSize * identifierByteSize
  }

  internal fun readPrimitiveArrayDumpRecord(
    objectId: Long,
    indexedObject: IndexedPrimitiveArray
  ): PrimitiveArrayDumpRecord {
    return readObjectRecord(objectId, indexedObject) {
      readPrimitiveArrayDumpRecord()
    }
  }

  internal fun readPrimitiveArrayByteSize(
    objectId: Long,
    indexedObject: IndexedPrimitiveArray
  ): Int {
    val cachedRecord = objectCache[objectId] as PrimitiveArrayDumpRecord?
    if (cachedRecord != null) {
      return when (cachedRecord) {
        is BooleanArrayDump -> cachedRecord.array.size * PrimitiveType.BOOLEAN.byteSize
        is CharArrayDump -> cachedRecord.array.size * PrimitiveType.CHAR.byteSize
        is FloatArrayDump -> cachedRecord.array.size * PrimitiveType.FLOAT.byteSize
        is DoubleArrayDump -> cachedRecord.array.size * PrimitiveType.DOUBLE.byteSize
        is ByteArrayDump -> cachedRecord.array.size * PrimitiveType.BYTE.byteSize
        is ShortArrayDump -> cachedRecord.array.size * PrimitiveType.SHORT.byteSize
        is IntArrayDump -> cachedRecord.array.size * PrimitiveType.INT.byteSize
        is LongArrayDump -> cachedRecord.array.size * PrimitiveType.LONG.byteSize
      }
    }
    val position = indexedObject.position + identifierByteSize + PrimitiveType.INT.byteSize
    val size = reader.readRecord(position, PrimitiveType.INT.byteSize.toLong()) {
      readInt()
    }
    return size * indexedObject.primitiveType.byteSize
  }

  internal fun readClassDumpRecord(
    objectId: Long,
    indexedObject: IndexedClass
  ): ClassDumpRecord {
    return readObjectRecord(objectId, indexedObject) {
      readClassDumpRecord()
    }
  }

  internal fun readInstanceDumpRecord(
    objectId: Long,
    indexedObject: IndexedInstance
  ): InstanceDumpRecord {
    return readObjectRecord(objectId, indexedObject) {
      readInstanceDumpRecord()
    }
  }

  private fun <T : ObjectRecord> readObjectRecord(
    objectId: Long,
    indexedObject: IndexedObject,
    readBlock: HprofRecordReader.() -> T
  ): T {
    val objectRecordOrNull = objectCache[objectId]
    @Suppress("UNCHECKED_CAST")
    if (objectRecordOrNull != null) {
      return objectRecordOrNull as T
    }
    return reader.readRecord(indexedObject.position, indexedObject.recordSize) {
      readBlock()
    }.apply { objectCache.put(objectId, this) }
  }

  private fun wrapIndexedObject(
    indexedObject: IndexedObject,
    objectId: Long
  ): HeapObject {
    return when (indexedObject) {
      is IndexedClass -> HeapClass(this, indexedObject, objectId)
      is IndexedInstance -> {
        val isPrimitiveWrapper = index.primitiveWrapperTypes.contains(indexedObject.classId)
        HeapInstance(this, indexedObject, objectId, isPrimitiveWrapper)
      }
      is IndexedObjectArray -> {
        val isPrimitiveWrapperArray =
          index.primitiveWrapperTypes.contains(indexedObject.arrayClassId)
        HeapObjectArray(this, indexedObject, objectId, isPrimitiveWrapperArray)
      }
      is IndexedPrimitiveArray -> HeapPrimitiveArray(this, indexedObject, objectId)
    }
  }

  companion object {

    /**
     * This is not a public API, it's only public so that we can evaluate the effectiveness of
     * different cache size in tests in a different module.
     *
     * LRU cache size of 3000 is a sweet spot to balance hits vs memory usage.
     * This is based on running InstrumentationLeakDetectorTest a bunch of time on a
     * Pixel 2 XL API 28. Hit count was ~120K, miss count ~290K
     */
    var INTERNAL_LRU_CACHE_SIZE = 3000

    /**
     * A facility for opening a [CloseableHeapGraph] from a [File].
     * This first parses the file headers with [HprofHeader.parseHeaderOf], then indexes the file content
     * with [HprofIndex.indexRecordsOf] and then opens a [CloseableHeapGraph] from the index, which
     * you are responsible for closing after using.
     */
    fun File.openHeapGraph(
      proguardMapping: ProguardMapping? = null,
      indexedGcRootTypes: Set<KClass<out GcRoot>> = HprofIndex.defaultIndexedGcRootTypes()
    ): CloseableHeapGraph {
      return FileSourceProvider(this).openHeapGraph(proguardMapping, indexedGcRootTypes)
    }

    fun DualSourceProvider.openHeapGraph(
      proguardMapping: ProguardMapping? = null,
      indexedGcRootTypes: Set<KClass<out GcRoot>> = HprofIndex.defaultIndexedGcRootTypes()
    ): CloseableHeapGraph {
      val header = openStreamingSource().use { HprofHeader.parseHeaderOf(it) }
      val index = HprofIndex.indexRecordsOf(this, header, proguardMapping, indexedGcRootTypes)
      return index.openHeapGraph()
    }

    @Deprecated(
        "Replaced by HprofIndex.indexRecordsOf().openHeapGraph() or File.openHeapGraph()",
        replaceWith = ReplaceWith(
            "HprofIndex.indexRecordsOf(hprof, proguardMapping, indexedGcRootTypes)" +
                ".openHeapGraph()"
        )
    )
    fun indexHprof(
      hprof: Hprof,
      proguardMapping: ProguardMapping? = null,
      indexedGcRootTypes: Set<KClass<out GcRoot>> = HprofIndex.defaultIndexedGcRootTypes()
    ): HeapGraph {

      val index =
        HprofIndex.indexRecordsOf(
            FileSourceProvider(hprof.file), hprof.header, proguardMapping, indexedGcRootTypes
        )
      val graph = index.openHeapGraph()
      hprof.attachClosable(graph)
      return graph
    }
  }
}
