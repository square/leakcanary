package leakcanary.internal

import leakcanary.HprofPushRecordsParser.OnRecordListener
import leakcanary.HprofReader
import leakcanary.PrimitiveType
import leakcanary.Record
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
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
import leakcanary.Record.LoadClassRecord
import leakcanary.Record.StringRecord
import leakcanary.internal.IndexedObject.IndexedClass
import leakcanary.internal.IndexedObject.IndexedInstance
import leakcanary.internal.IndexedObject.IndexedObjectArray
import leakcanary.internal.IndexedObject.IndexedPrimitiveArray
import kotlin.reflect.KClass

/**
 * This class is not thread safe, should be used from a single thread.
 */
internal class HprofInMemoryIndex private constructor(
  private val hprofStringCache: LongToStringSparseArray,
  private val classNames: LongToLongSparseArray,
  private val objectIndex: LongToObjectSparseArray<IndexedObject>,
  private val typeSizes: Map<Int, Int>,
  val primitiveWrapperTypes: Set<Long>
) {
  val idSize: Int
    get() = typeSizes.getValue(HprofReader.OBJECT_TYPE)

  fun sizeOfFieldType(hprofType: Int): Int = typeSizes.getValue(hprofType)

  fun hprofStringById(id: Long): String {
    return hprofStringCache[id] ?: throw IllegalArgumentException("Hprof string $id not in cache")
  }

  fun className(classId: Long): String {
    // String, primitive types
    return hprofStringById(classNames[classId])
  }

  fun classId(className: String): Long? {
    // Note: this performs two linear scans over arrays
    return hprofStringCache.getKey(className)
        ?.let { stringId -> classNames.getKey(stringId) }
  }

  fun indexedClassSequence(): Sequence<Pair<Long, IndexedClass>> {
    return objectIndex.entrySequence()
        .filter { it.second is IndexedClass }
        .map { it.first to it.second as IndexedClass }
  }

  fun indexedInstanceSequence(): Sequence<Pair<Long, IndexedInstance>> {
    return objectIndex.entrySequence()
        .filter { it.second is IndexedInstance }
        .map { it.first to it.second as IndexedInstance }
  }

  fun indexedObject(objectId: Long): IndexedObject {
    return objectIndex[objectId]
  }

  class Builder : OnRecordListener {
    /**
     * Map of string id to string
     * This currently keeps all the hprof strings that we could care about: class names,
     * static field names and instance fields names
     */
    // TODO Replacing with a radix trie reversed into a sparse array of long to trie leaf could save
    // memory.
    // Another option is to switch back to reading from the file system as necessary, and keep a much
    // smaller cache for strings we need during shortest path (those are for exclusions)
    private val hprofStringCache = LongToStringSparseArray(60000)

    /**
     * class id to string id
     */
    private val classNames = LongToLongSparseArray(20000)

    /**
     * Object id to [IndexedObject].
     * The id can be for classes instances, classes, object arrays and primitive arrays
     */
    private val objectIndex = LongToObjectSparseArray<IndexedObject>(250000)

    /**
     * Class ids for primitive wrapper types
     */
    private val primitiveWrapperTypes = mutableSetOf<Long>()

    /**
     * String ids for class names of primitive wrapper types
     */
    private val primitiveWrapperClassNames = mutableSetOf<Long>()

    private lateinit var typeSizes: Map<Int, Int>
    private var consumed = false

    override fun recordTypes(): Set<KClass<out Record>> = setOf(
        StringRecord::class,
        LoadClassRecord::class,
        ClassDumpRecord::class,
        InstanceDumpRecord::class,
        ObjectArrayDumpRecord::class,
        PrimitiveArrayDumpRecord::class
    )

    override fun onTypeSizesAvailable(typeSizes: Map<Int, Int>) {
      this.typeSizes = typeSizes
    }

    override fun onRecord(
      position: Long,
      record: Record
    ) {
      if (consumed) {
        throw IllegalStateException("This builder instance already produced a HprofInMemoryIndex")
      }
      when (record) {
        is StringRecord -> {
          if (PRIMITIVE_WRAPPER_TYPES.contains(record.string)) {
            primitiveWrapperClassNames.add(record.id)
          }
          hprofStringCache[record.id] = record.string
        }
        is LoadClassRecord -> {
          classNames[record.id] = record.classNameStringId
          if (primitiveWrapperClassNames.contains(record.classNameStringId)) {
            primitiveWrapperTypes.add(record.id)
          }
        }
        is ClassDumpRecord -> {
          objectIndex[record.id] = IndexedClass(position, record.superClassId, record.instanceSize)
        }
        is InstanceDumpRecord -> {
          objectIndex[record.id] =
            IndexedInstance(position, record.classId)
        }
        is ObjectArrayDumpRecord -> {
          objectIndex[record.id] = IndexedObjectArray(position, record.arrayClassId)
        }
        is PrimitiveArrayDumpRecord -> {
          val primitiveType = when (record) {
            is BooleanArrayDump -> PrimitiveType.BOOLEAN
            is CharArrayDump -> PrimitiveType.CHAR
            is FloatArrayDump -> PrimitiveType.FLOAT
            is DoubleArrayDump -> PrimitiveType.DOUBLE
            is ByteArrayDump -> PrimitiveType.BYTE
            is ShortArrayDump -> PrimitiveType.SHORT
            is IntArrayDump -> PrimitiveType.INT
            is LongArrayDump -> PrimitiveType.LONG
          }
          objectIndex[record.id] = IndexedPrimitiveArray(position, primitiveType)
        }
      }
    }

    fun buildIndex(): HprofInMemoryIndex {
      consumed = true
      // Passing references to avoid copying the underlying data structures.
      return HprofInMemoryIndex(
          hprofStringCache, classNames, objectIndex,
          typeSizes,
          primitiveWrapperTypes
      )
    }

  }

  companion object {

    private val PRIMITIVE_WRAPPER_TYPES = setOf<String>(
        Boolean::class.java.name, Char::class.java.name, Float::class.java.name,
        Double::class.java.name, Byte::class.java.name, Short::class.java.name,
        Int::class.java.name, Long::class.java.name
    )

    fun createOnRecordListener(): Builder {
      return Builder()
    }

  }
}