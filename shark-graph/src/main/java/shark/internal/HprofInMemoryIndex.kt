package shark.internal

import shark.GcRoot
import shark.HprofHeader
import shark.HprofRecord
import shark.HprofRecord.HeapDumpRecord.GcRootRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassSkipContentRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceSkipContentRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArraySkipContentRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArraySkipContentRecord
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StringRecord
import shark.HprofVersion.ANDROID
import shark.StreamingHprofReader
import shark.OnHprofRecordListener
import shark.PrimitiveType
import shark.ProguardMapping
import shark.ValueHolder
import shark.internal.IndexedObject.IndexedClass
import shark.internal.IndexedObject.IndexedInstance
import shark.internal.IndexedObject.IndexedObjectArray
import shark.internal.IndexedObject.IndexedPrimitiveArray
import shark.internal.hppc.LongLongScatterMap
import shark.internal.hppc.LongObjectPair
import shark.internal.hppc.LongObjectScatterMap
import shark.internal.hppc.LongScatterSet
import shark.internal.hppc.to
import kotlin.math.max
import kotlin.reflect.KClass

/**
 * This class is not thread safe, should be used from a single thread.
 */
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
  val primitiveWrapperTypes: LongScatterSet,
  private val bytesForClassSize: Int,
  private val bytesForInstanceSize: Int,
  private val bytesForObjectArraySize: Int,
  private val bytesForPrimitiveArraySize: Int,
  private val useForwardSlashClassPackageSeparator: Boolean
) {

  val classCount: Int
    get() = classIndex.size

  val instanceCount: Int
    get() = instanceIndex.size

  val objectArrayCount: Int
    get() = objectArrayIndex.size

  val primitiveArrayCount: Int
    get() = primitiveArrayIndex.size

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
    return (proguardMapping?.deobfuscateClassName(classNameString) ?: classNameString).run {
      if (useForwardSlashClassPackageSeparator) {
        // JVM heap dumps use "/" for package separators (vs "." for Android heap dumps)
        replace('/', '.')
      } else this
    }
  }

  fun classId(className: String): Long? {
    val internalClassName = if (useForwardSlashClassPackageSeparator) {
      // JVM heap dumps use "/" for package separators (vs "." for Android heap dumps)
      className.replace('.', '/')
    } else className

    // Note: this performs two linear scans over arrays
    val hprofStringId = hprofStringCache.entrySequence()
        .firstOrNull { it.second == internalClassName }
        ?.first
    return hprofStringId?.let { stringId ->
      classNames.entrySequence()
          .firstOrNull { it.second == stringId }
          ?.first
    }
  }

  fun indexedClassSequence(): Sequence<LongObjectPair<IndexedClass>> {
    return classIndex.entrySequence()
        .map {
          val id = it.first
          val array = it.second
          id to IndexedClass(
              position = array.readTruncatedLong(positionSize),
              superclassId = array.readId(),
              instanceSize = array.readInt(),
              recordSize = array.readTruncatedLong(bytesForClassSize)
          )
        }
  }

  fun indexedInstanceSequence(): Sequence<LongObjectPair<IndexedInstance>> {
    return instanceIndex.entrySequence()
        .map {
          val id = it.first
          val array = it.second
          val instance = IndexedInstance(
              position = array.readTruncatedLong(positionSize),
              classId = array.readId(),
              recordSize = array.readTruncatedLong(bytesForInstanceSize)
          )
          id to instance
        }
  }

  fun indexedObjectArraySequence(): Sequence<LongObjectPair<IndexedObjectArray>> {
    return objectArrayIndex.entrySequence()
        .map {
          val id = it.first
          val array = it.second
          val objectArray = IndexedObjectArray(
              position = array.readTruncatedLong(positionSize),
              arrayClassId = array.readId(),
              recordSize = array.readTruncatedLong(bytesForObjectArraySize)
          )
          id to objectArray
        }
  }

  fun indexedPrimitiveArraySequence(): Sequence<LongObjectPair<IndexedPrimitiveArray>> {
    return primitiveArrayIndex.entrySequence()
        .map {
          val id = it.first
          val array = it.second

          val primitiveArray = IndexedPrimitiveArray(
              position = array.readTruncatedLong(positionSize),
              primitiveType = PrimitiveType.values()[array.readByte()
                  .toInt()],
              recordSize = array.readTruncatedLong(bytesForPrimitiveArraySize)
          )
          id to primitiveArray
        }
  }

  fun indexedObjectSequence(): Sequence<LongObjectPair<IndexedObject>> {
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
          instanceSize = array.readInt(),
          recordSize = array.readTruncatedLong(bytesForClassSize)
      )
    }
    array = instanceIndex[objectId]
    if (array != null) {
      return IndexedInstance(
          position = array.readTruncatedLong(positionSize),
          classId = array.readId(),
          recordSize = array.readTruncatedLong(bytesForInstanceSize)
      )
    }
    array = objectArrayIndex[objectId]
    if (array != null) {
      return IndexedObjectArray(
          position = array.readTruncatedLong(positionSize),
          arrayClassId = array.readId(),
          recordSize = array.readTruncatedLong(bytesForObjectArraySize)
      )
    }
    array = primitiveArrayIndex[objectId]
    if (array != null) {
      return IndexedPrimitiveArray(
          position = array.readTruncatedLong(positionSize),
          primitiveType = PrimitiveType.values()[array.readByte()
              .toInt()],
          recordSize = array.readTruncatedLong(bytesForPrimitiveArraySize)
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
    maxPosition: Long,
    classCount: Int,
    instanceCount: Int,
    objectArrayCount: Int,
    primitiveArrayCount: Int,
    private val indexedGcRootsTypes: Set<Class<out GcRoot>>,
    val bytesForClassSize: Int,
    val bytesForInstanceSize: Int,
    val bytesForObjectArraySize: Int,
    val bytesForPrimitiveArraySize: Int
  ) : OnHprofRecordListener {

    private val identifierSize = if (longIdentifiers) 8 else 4
    private val positionSize = byteSizeForUnsigned(maxPosition)

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
    private val classNames = LongLongScatterMap(expectedElements = classCount)

    private val classIndex = UnsortedByteEntries(
        bytesPerValue = positionSize + identifierSize + 4 + bytesForClassSize,
        longIdentifiers = longIdentifiers,
        initialCapacity = classCount
    )
    private val instanceIndex = UnsortedByteEntries(
        bytesPerValue = positionSize + identifierSize + bytesForInstanceSize,
        longIdentifiers = longIdentifiers,
        initialCapacity = instanceCount
    )
    private val objectArrayIndex = UnsortedByteEntries(
        bytesPerValue = positionSize + identifierSize + bytesForObjectArraySize,
        longIdentifiers = longIdentifiers,
        initialCapacity = objectArrayCount
    )
    private val primitiveArrayIndex = UnsortedByteEntries(
        bytesPerValue = positionSize + 1 + bytesForPrimitiveArraySize,
        longIdentifiers = longIdentifiers,
        initialCapacity = primitiveArrayCount
    )

    /**
     * Class ids for primitive wrapper types
     */
    private val primitiveWrapperTypes = LongScatterSet()

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
          hprofStringCache[record.id] = record.string
        }
        is LoadClassRecord -> {
          classNames[record.id] = record.classNameStringId
          if (primitiveWrapperClassNames.contains(record.classNameStringId)) {
            primitiveWrapperTypes.add(record.id)
          }
        }
        is GcRootRecord -> {
          val gcRoot = record.gcRoot
          if (gcRoot.id != ValueHolder.NULL_REFERENCE
              && indexedGcRootsTypes.contains(gcRoot.javaClass)
          ) {
            gcRoots += gcRoot
          }
        }
        is ClassSkipContentRecord -> {
          classIndex.append(record.id)
              .apply {
                writeTruncatedLong(position, positionSize)
                writeId(record.superclassId)
                writeInt(record.instanceSize)
                writeTruncatedLong(record.recordSize, bytesForClassSize)
              }
        }
        is InstanceSkipContentRecord -> {
          instanceIndex.append(record.id)
              .apply {
                writeTruncatedLong(position, positionSize)
                writeId(record.classId)
                writeTruncatedLong(record.recordSize, bytesForInstanceSize)
              }
        }
        is ObjectArraySkipContentRecord -> {
          objectArrayIndex.append(record.id)
              .apply {
                writeTruncatedLong(position, positionSize)
                writeId(record.arrayClassId)
                writeTruncatedLong(record.recordSize, bytesForObjectArraySize)
              }
        }
        is PrimitiveArraySkipContentRecord -> {
          primitiveArrayIndex.append(record.id)
              .apply {
                writeTruncatedLong(position, positionSize)
                writeByte(record.type.ordinal.toByte())
                writeTruncatedLong(record.recordSize, bytesForPrimitiveArraySize)
              }
        }
      }
    }

    fun buildIndex(
      proguardMapping: ProguardMapping?,
      hprofHeader: HprofHeader
    ): HprofInMemoryIndex {
      val sortedInstanceIndex = instanceIndex.moveToSortedMap()
      val sortedObjectArrayIndex = objectArrayIndex.moveToSortedMap()
      val sortedPrimitiveArrayIndex = primitiveArrayIndex.moveToSortedMap()
      val sortedClassIndex = classIndex.moveToSortedMap()
      // Passing references to avoid copying the underlying data structures.
      return HprofInMemoryIndex(
          positionSize = positionSize,
          hprofStringCache = hprofStringCache,
          classNames = classNames,
          classIndex = sortedClassIndex,
          instanceIndex = sortedInstanceIndex,
          objectArrayIndex = sortedObjectArrayIndex,
          primitiveArrayIndex = sortedPrimitiveArrayIndex,
          gcRoots = gcRoots,
          proguardMapping = proguardMapping,
          primitiveWrapperTypes = primitiveWrapperTypes,
          bytesForClassSize = bytesForClassSize,
          bytesForInstanceSize = bytesForInstanceSize,
          bytesForObjectArraySize = bytesForObjectArraySize,
          bytesForPrimitiveArraySize = bytesForPrimitiveArraySize,
          useForwardSlashClassPackageSeparator = hprofHeader.version != ANDROID
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

    fun indexHprof(
      reader: StreamingHprofReader,
      hprofHeader: HprofHeader,
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

      // First pass to count and correctly size arrays once and for all.
      var maxClassSize = 0L
      var maxInstanceSize = 0L
      var maxObjectArraySize = 0L
      var maxPrimitiveArraySize = 0L
      var classCount = 0
      var instanceCount = 0
      var objectArrayCount = 0
      var primitiveArrayCount = 0

      val bytesRead = reader.readRecords(setOf(
          ClassSkipContentRecord::class,
          InstanceSkipContentRecord::class,
          ObjectArraySkipContentRecord::class,
          PrimitiveArraySkipContentRecord::class
      ), OnHprofRecordListener { _, record ->
        when (record) {
          is ClassSkipContentRecord -> {
            classCount++
            maxClassSize = max(maxClassSize, record.recordSize)
          }
          is InstanceSkipContentRecord -> {
            instanceCount++
            maxInstanceSize = max(maxInstanceSize, record.recordSize)
          }
          is ObjectArraySkipContentRecord -> {
            objectArrayCount++
            maxObjectArraySize = max(maxObjectArraySize, record.recordSize)
          }
          is PrimitiveArraySkipContentRecord -> {
            primitiveArrayCount++
            maxPrimitiveArraySize = max(maxPrimitiveArraySize, record.recordSize)
          }
        }
      })

      val bytesForClassSize = byteSizeForUnsigned(maxClassSize)
      val bytesForInstanceSize = byteSizeForUnsigned(maxInstanceSize)
      val bytesForObjectArraySize = byteSizeForUnsigned(maxObjectArraySize)
      val bytesForPrimitiveArraySize = byteSizeForUnsigned(maxPrimitiveArraySize)

      val indexBuilderListener = Builder(
          longIdentifiers = hprofHeader.identifierByteSize == 8,
          maxPosition = bytesRead,
          classCount = classCount,
          instanceCount = instanceCount,
          objectArrayCount = objectArrayCount,
          primitiveArrayCount = primitiveArrayCount,
          indexedGcRootsTypes = indexedGcRootTypes.map { it.java }
              .toSet(),
          bytesForClassSize = bytesForClassSize,
          bytesForInstanceSize = bytesForInstanceSize,
          bytesForObjectArraySize = bytesForObjectArraySize,
          bytesForPrimitiveArraySize = bytesForPrimitiveArraySize
      )

      reader.readRecords(recordTypes, indexBuilderListener)
      return indexBuilderListener.buildIndex(proguardMapping, hprofHeader)
    }

  }
}