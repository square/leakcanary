package shark.internal

import shark.GcRoot
import shark.Hprof
import shark.HprofRecord
import shark.HprofRecord.HeapDumpRecord.GcRootRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassSkipContentRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceSkipContentRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArraySkipContentRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArraySkipContentRecord
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StringRecord
import shark.OnHprofRecordListener
import shark.PrimitiveType
import shark.ProguardMapping
import shark.ValueHolder
import shark.internal.IndexedObject.IndexedClass
import shark.internal.IndexedObject.IndexedInstance
import shark.internal.IndexedObject.IndexedObjectArray
import shark.internal.IndexedObject.IndexedPrimitiveArray
import shark.internal.hppc.LongLongScatterMap
import shark.internal.hppc.LongObjectScatterMap
import kotlin.reflect.KClass

/**
 * This class is not thread safe, should be used from a single thread.
 */
@Suppress("TooManyFunctions")
internal class HprofInMemoryIndex private constructor(
  private val positionSize: Int,
  private val hprofStringCache: LongObjectScatterMap<String>,
  private val classNames: LongLongScatterMap,
  private val classIndex: SortedBytesMap,
  private val instanceIndex: SortedBytesMap,
  private val objectArrayIndex: SortedBytesMap,
  private val primitiveArrayIndex: SortedBytesMap,
  private val gcRoots: List<GcRoot>,
  private val proguardMapping: ProguardMapping?,
  val primitiveWrapperTypes: Set<Long>
) {

  fun fieldName(
    classId: Long,
    id: Long
  ): String {
    val fieldNameString = hprofStringById(id)
    return proguardMapping?.let {
      val classNameStringId = classNames[classId]
      val classNameString = hprofStringById(classNameStringId)
      proguardMapping.deobfuscateFieldName(classNameString, fieldNameString)
    } ?: fieldNameString
  }

  fun className(classId: Long): String {
    // String, primitive types
    val classNameStringId = classNames[classId]
    val classNameString = hprofStringById(classNameStringId)
    return proguardMapping?.deobfuscateClassName(classNameString) ?: classNameString
  }

  fun classId(className: String): Long? {
    // Note: this performs two linear scans over arrays
    val hprofStringId = hprofStringCache.entrySequence()
        .firstOrNull { it.second == className }
        ?.first
    return hprofStringId?.let { stringId ->
      classNames.entrySequence()
          .firstOrNull { it.second == stringId }
          ?.first
    }
  }

  fun indexedClassSequence(): Sequence<Pair<Long, IndexedClass>> {
    return classIndex.entrySequence()
        .map {
          val id = it.first
          val array = it.second
          id to IndexedClass(
              position = array.readTruncatedLong(positionSize),
              superclassId = array.readId(),
              instanceSize = array.readInt()
          )
        }
  }

  fun indexedInstanceSequence(): Sequence<Pair<Long, IndexedInstance>> {
    return instanceIndex.entrySequence()
        .map {
          val id = it.first
          val array = it.second
          val instance = IndexedInstance(
              position = array.readTruncatedLong(positionSize),
              classId = array.readId()
          )
          id to instance
        }
  }

  fun indexedObjectArraySequence(): Sequence<Pair<Long, IndexedObjectArray>> {
    return objectArrayIndex.entrySequence()
        .map {
          val id = it.first
          val array = it.second
          val objectArray = IndexedObjectArray(
              position = array.readTruncatedLong(positionSize),
              arrayClassId = array.readId()
          )
          id to objectArray
        }
  }

  fun indexedPrimitiveArraySequence(): Sequence<Pair<Long, IndexedPrimitiveArray>> {
    return primitiveArrayIndex.entrySequence()
        .map {
          val id = it.first
          val array = it.second

          val primitiveArray = IndexedPrimitiveArray(
              position = array.readTruncatedLong(positionSize),
              primitiveType = PrimitiveType.values()[array.readByte().toInt()]
          )
          id to primitiveArray
        }
  }

  fun indexedObjectSequence(): Sequence<Pair<Long, IndexedObject>> {
    return indexedClassSequence() +
        indexedInstanceSequence() +
        indexedObjectArraySequence() +
        indexedPrimitiveArraySequence()
  }

  fun gcRoots(): List<GcRoot> {
    return gcRoots
  }

  @Suppress("ReturnCount")
  fun indexedObjectOrNull(objectId: Long): IndexedObject? {
    var array: ByteSubArray? = classIndex[objectId]
    if (array != null) {
      return IndexedClass(
          position = array.readTruncatedLong(positionSize),
          superclassId = array.readId(),
          instanceSize = array.readInt()
      )
    }
    array = instanceIndex[objectId]
    if (array != null) {
      return IndexedInstance(
          position = array.readTruncatedLong(positionSize),
          classId = array.readId()
      )
    }
    array = objectArrayIndex[objectId]
    if (array != null) {
      return IndexedObjectArray(
          position = array.readTruncatedLong(positionSize),
          arrayClassId = array.readId()
      )
    }
    array = primitiveArrayIndex[objectId]
    if (array != null) {
      return IndexedPrimitiveArray(
          position = array.readTruncatedLong(positionSize),
          primitiveType = PrimitiveType.values()[array.readByte().toInt()]
      )
    }
    return null
  }

  @Suppress("ReturnCount")
  fun objectIdIsIndexed(objectId: Long): Boolean {
    if (classIndex[objectId] != null) {
      return true
    }
    if (instanceIndex[objectId] != null) {
      return true
    }
    if (objectArrayIndex[objectId] != null) {
      return true
    }
    if (primitiveArrayIndex[objectId] != null) {
      return true
    }
    return false
  }

  private fun hprofStringById(id: Long): String {
    return hprofStringCache[id] ?: throw IllegalArgumentException("Hprof string $id not in cache")
  }

  private class Builder(
    longIdentifiers: Boolean,
    fileLength: Long,
    classCount: Int,
    instanceCount: Int,
    objectArrayCount: Int,
    primitiveArrayCount: Int,
    private val indexedGcRootsTypes: Set<KClass<out GcRoot>>
  ) : OnHprofRecordListener {

    private val identifierSize = if (longIdentifiers) 8 else 4
    private val positionSize = byteSizeForUnsigned(fileLength)

    /**
     * Map of string id to string
     * This currently keeps all the hprof strings that we could care about: class names,
     * static field names and instance fields names
     */
    // TODO Replacing with a radix trie reversed into a sparse array of long to trie leaf could save
    // memory. Can be stored as 3 arrays: array of keys, array of values which are indexes into
    // a large array of string bytes. Each "entry" consists of a size, the index of the previous
    // segment and then the segment content.

    private val hprofStringCache = LongObjectScatterMap<String>()

    /**
     * class id to string id
     */
    private val classNames = LongLongScatterMap()

    private val classIndex = UnsortedByteEntries(
        bytesPerValue = positionSize + identifierSize + 4,
        longIdentifiers = longIdentifiers,
        initialCapacity = classCount
    )
    private val instanceIndex = UnsortedByteEntries(
        bytesPerValue = positionSize + identifierSize,
        longIdentifiers = longIdentifiers,
        initialCapacity = instanceCount
    )
    private val objectArrayIndex = UnsortedByteEntries(
        bytesPerValue = positionSize + identifierSize,
        longIdentifiers = longIdentifiers,
        initialCapacity = objectArrayCount
    )
    private val primitiveArrayIndex = UnsortedByteEntries(
        bytesPerValue = positionSize + 1,
        longIdentifiers = longIdentifiers,
        initialCapacity = primitiveArrayCount
    )

    /**
     * Class ids for primitive wrapper types
     */
    private val primitiveWrapperTypes = mutableSetOf<Long>()

    /**
     * String ids for class names of primitive wrapper types
     */
    private val primitiveWrapperClassNames = mutableSetOf<Long>()

    private val gcRoots = mutableListOf<GcRoot>()

    override fun onHprofRecord(
      position: Long,
      record: HprofRecord
    ) {
      when (record) {
        is StringRecord -> {
          if (PRIMITIVE_WRAPPER_TYPES.contains(record.string)) {
            primitiveWrapperClassNames.add(record.id)
          }
          // JVM heap dumps use "/" for package separators (vs "." for Android heap dumps)
          hprofStringCache[record.id] = record.string.replace('/', '.')
        }
        is LoadClassRecord -> {
          classNames[record.id] = record.classNameStringId
          if (primitiveWrapperClassNames.contains(record.classNameStringId)) {
            primitiveWrapperTypes.add(record.id)
          }
        }
        is GcRootRecord -> {
          val gcRoot = record.gcRoot
          if (gcRoot.id != ValueHolder.NULL_REFERENCE && gcRoot::class in indexedGcRootsTypes) {
            gcRoots += gcRoot
          }
        }
        is ClassSkipContentRecord -> {
          classIndex.append(record.id)
              .apply {
                writeTruncatedLong(position, positionSize)
                writeId(record.superclassId)
                writeInt(record.instanceSize)
              }
        }
        is InstanceSkipContentRecord -> {
          instanceIndex.append(record.id)
              .apply {
                writeTruncatedLong(position, positionSize)
                writeId(record.classId)
              }
        }
        is ObjectArraySkipContentRecord -> {
          objectArrayIndex.append(record.id)
              .apply {
                writeTruncatedLong(position, positionSize)
                writeId(record.arrayClassId)
              }
        }
        is PrimitiveArraySkipContentRecord -> {
          primitiveArrayIndex.append(record.id)
              .apply {
                writeTruncatedLong(position, positionSize)
                writeByte(record.type.ordinal.toByte())
              }
        }
      }
    }

    fun buildIndex(
      proguardMapping: ProguardMapping?
    ): HprofInMemoryIndex {
      val sortedInstanceIndex = instanceIndex.moveToSortedMap()
      val sortedObjectArrayIndex = objectArrayIndex.moveToSortedMap()
      val sortedPrimitiveArrayIndex = primitiveArrayIndex.moveToSortedMap()
      val sortedClassIndex = classIndex.moveToSortedMap()
      // Passing references to avoid copying the underlying data structures.
      return HprofInMemoryIndex(
          positionSize,
          hprofStringCache, classNames, sortedClassIndex, sortedInstanceIndex,
          sortedObjectArrayIndex,
          sortedPrimitiveArrayIndex, gcRoots,
          proguardMapping,
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

    private fun byteSizeForUnsigned(maxValue: Long): Int {
      var value = maxValue
      var byteCount = 0
      while (value != 0L) {
        value = value shr 8
        byteCount++
      }
      return byteCount
    }

    fun createReadingHprof(
      hprof: Hprof,
      proguardMapping: ProguardMapping?,
      indexedGcRootTypes: Set<KClass<out GcRoot>>
    ): HprofInMemoryIndex {
      val recordTypes = setOf(
          StringRecord::class,
          LoadClassRecord::class,
          ClassSkipContentRecord::class,
          InstanceSkipContentRecord::class,
          ObjectArraySkipContentRecord::class,
          PrimitiveArraySkipContentRecord::class,
          GcRootRecord::class
      )
      val reader = hprof.reader

      // First pass to count and correctly size arrays once and for all.
      var classCount = 0
      var instanceCount = 0
      var objectArrayCount = 0
      var primitiveArrayCount = 0
      reader.readHprofRecords(setOf(
          LoadClassRecord::class,
          InstanceSkipContentRecord::class,
          ObjectArraySkipContentRecord::class,
          PrimitiveArraySkipContentRecord::class
      ), OnHprofRecordListener { position, record ->
        when (record) {
          is LoadClassRecord -> classCount++
          is InstanceSkipContentRecord -> instanceCount++
          is ObjectArraySkipContentRecord -> objectArrayCount++
          is PrimitiveArraySkipContentRecord -> primitiveArrayCount++
        }
      })

      hprof.moveReaderTo(reader.startPosition)
      val indexBuilderListener =
        Builder(
            reader.identifierByteSize == 8, hprof.fileLength, classCount, instanceCount,
            objectArrayCount, primitiveArrayCount, indexedGcRootTypes
        )

      reader.readHprofRecords(recordTypes, indexBuilderListener)

      return indexBuilderListener.buildIndex(proguardMapping)
    }

  }
}