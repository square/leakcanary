package shark

import shark.GcRoot.JavaFrame
import shark.GcRoot.JniGlobal
import shark.GcRoot.JniLocal
import shark.GcRoot.JniMonitor
import shark.GcRoot.MonitorUsed
import shark.GcRoot.NativeStack
import shark.GcRoot.StickyClass
import shark.GcRoot.ThreadBlock
import shark.GcRoot.ThreadObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofHeapGraph.Companion.indexHprof
import shark.HprofRecord.HeapDumpRecord.ObjectRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import shark.internal.FieldValuesReader
import shark.internal.HprofInMemoryIndex
import shark.internal.IndexedObject
import shark.internal.IndexedObject.IndexedClass
import shark.internal.IndexedObject.IndexedInstance
import shark.internal.IndexedObject.IndexedObjectArray
import shark.internal.IndexedObject.IndexedPrimitiveArray
import shark.internal.LruCache
import kotlin.reflect.KClass

/**
 * A [HeapGraph] that reads from an indexed [Hprof]. Create a new instance with [indexHprof].
 */
@Suppress("TooManyFunctions")
class HprofHeapGraph internal constructor(
  private val hprof: Hprof,
  private val index: HprofInMemoryIndex
) : HeapGraph {

  override val identifierByteSize: Int get() = hprof.reader.identifierByteSize

  override val context = GraphContext()

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

  // LRU cache size of 3000 is a sweet spot to balance hits vs memory usage.
  // This is based on running InstrumentationLeakDetectorTest a bunch of time on a
  // Pixel 2 XL API 28. Hit count was ~120K, miss count ~290K
  private val objectCache = LruCache<Long, ObjectRecord>(3000)

  override fun findObjectById(objectId: Long): HeapObject {
    return findObjectByIdOrNull(objectId) ?: throw IllegalArgumentException(
        "Object id $objectId not found in heap dump."
    )
  }

  override fun findObjectByIdOrNull(objectId: Long): HeapObject? {
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
      hprof.reader.readObjectArrayDumpRecord()
    }
  }

  internal fun readPrimitiveArrayDumpRecord(
    objectId: Long,
    indexedObject: IndexedPrimitiveArray
  ): PrimitiveArrayDumpRecord {
    return readObjectRecord(objectId, indexedObject) {
      hprof.reader.readPrimitiveArrayDumpRecord()
    }
  }

  internal fun readClassDumpRecord(
    objectId: Long,
    indexedObject: IndexedClass
  ): ClassDumpRecord {
    return readObjectRecord(objectId, indexedObject) {
      hprof.reader.readClassDumpRecord()
    }
  }

  internal fun readInstanceDumpRecord(
    objectId: Long,
    indexedObject: IndexedInstance
  ): InstanceDumpRecord {
    return readObjectRecord(objectId, indexedObject) {
      hprof.reader.readInstanceDumpRecord()
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
    hprof.moveReaderTo(indexedObject.position)
    return readBlock().apply { objectCache.put(objectId, this) }
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
    fun indexHprof(
      hprof: Hprof,
      proguardMapping: ProguardMapping? = null,
      indexedGcRootTypes: Set<KClass<out GcRoot>> = setOf(
          JniGlobal::class,
          JavaFrame::class,
          JniLocal::class,
          MonitorUsed::class,
          NativeStack::class,
          StickyClass::class,
          ThreadBlock::class,
          // ThreadObject points to threads, which we need to find the thread that a JavaLocalPattern
          // belongs to
          ThreadObject::class,
          JniMonitor::class
          /*
          Not included here:

          VmInternal: Ignoring because we've got 150K of it, but is this the right thing
          to do? What's VmInternal exactly? History does not go further than
          https://android.googlesource.com/platform/dalvik2/+/refs/heads/master/hit/src/com/android/hit/HprofParser.java#77
          We should log to figure out what objects VmInternal points to.

          ReferenceCleanup: We used to keep it, but the name doesn't seem like it should create a leak.

          Unknown: it's unknown, should we care?

          We definitely don't care about those for leak finding: InternedString, Finalizing, Debugger, Unreachable
           */
      )
    ): HeapGraph {
      val index = HprofInMemoryIndex.createReadingHprof(hprof, proguardMapping, indexedGcRootTypes)
      return HprofHeapGraph(hprof, index)
    }
  }

}