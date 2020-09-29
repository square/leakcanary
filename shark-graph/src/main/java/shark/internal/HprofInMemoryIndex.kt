package shark.internal

import shark.GcRoot
import shark.HprofHeader
import shark.HprofRecordReader
import shark.HprofRecordTag
import shark.HprofRecordTag.CLASS_DUMP
import shark.HprofRecordTag.INSTANCE_DUMP
import shark.HprofRecordTag.LOAD_CLASS
import shark.HprofRecordTag.OBJECT_ARRAY_DUMP
import shark.HprofRecordTag.PRIMITIVE_ARRAY_DUMP
import shark.HprofRecordTag.ROOT_DEBUGGER
import shark.HprofRecordTag.ROOT_FINALIZING
import shark.HprofRecordTag.ROOT_INTERNED_STRING
import shark.HprofRecordTag.ROOT_JAVA_FRAME
import shark.HprofRecordTag.ROOT_JNI_GLOBAL
import shark.HprofRecordTag.ROOT_JNI_LOCAL
import shark.HprofRecordTag.ROOT_JNI_MONITOR
import shark.HprofRecordTag.ROOT_MONITOR_USED
import shark.HprofRecordTag.ROOT_NATIVE_STACK
import shark.HprofRecordTag.ROOT_REFERENCE_CLEANUP
import shark.HprofRecordTag.ROOT_STICKY_CLASS
import shark.HprofRecordTag.ROOT_THREAD_BLOCK
import shark.HprofRecordTag.ROOT_THREAD_OBJECT
import shark.HprofRecordTag.ROOT_UNKNOWN
import shark.HprofRecordTag.ROOT_UNREACHABLE
import shark.HprofRecordTag.ROOT_VM_INTERNAL
import shark.HprofRecordTag.STRING_IN_UTF8
import shark.HprofVersion.ANDROID
import shark.OnHprofRecordTagListener
import shark.PrimitiveType
import shark.PrimitiveType.INT
import shark.ProguardMapping
import shark.StreamingHprofReader
import shark.ValueHolder
import shark.internal.IndexedObject.IndexedClass
import shark.internal.IndexedObject.IndexedInstance
import shark.internal.IndexedObject.IndexedObjectArray
import shark.internal.IndexedObject.IndexedPrimitiveArray
import shark.internal.hppc.IntObjectPair
import shark.internal.hppc.LongLongScatterMap
import shark.internal.hppc.LongObjectPair
import shark.internal.hppc.LongObjectScatterMap
import shark.internal.hppc.to
import java.util.EnumSet
import kotlin.math.max

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
  private val bytesForClassSize: Int,
  private val bytesForInstanceSize: Int,
  private val bytesForObjectArraySize: Int,
  private val bytesForPrimitiveArraySize: Int,
  private val useForwardSlashClassPackageSeparator: Boolean,
  val classFieldsReader: ClassFieldsReader,
  private val classFieldsIndexSize: Int
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
          id to array.readClass()
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

  fun objectAtIndex(index: Int): LongObjectPair<IndexedObject> {
    require(index > 0)
    if (index < classIndex.size) {
      val objectId = classIndex.keyAt(index)
      val array = classIndex.getAtIndex(index)
      return objectId to array.readClass()
    }
    var shiftedIndex = index - classIndex.size
    if (shiftedIndex < instanceIndex.size) {
      val objectId = instanceIndex.keyAt(shiftedIndex)
      val array = instanceIndex.getAtIndex(shiftedIndex)
      return objectId to IndexedInstance(
          position = array.readTruncatedLong(positionSize),
          classId = array.readId(),
          recordSize = array.readTruncatedLong(bytesForInstanceSize)
      )
    }
    shiftedIndex -= instanceIndex.size
    if (shiftedIndex < objectArrayIndex.size) {
      val objectId = objectArrayIndex.keyAt(shiftedIndex)
      val array = objectArrayIndex.getAtIndex(shiftedIndex)
      return objectId to IndexedObjectArray(
          position = array.readTruncatedLong(positionSize),
          arrayClassId = array.readId(),
          recordSize = array.readTruncatedLong(bytesForObjectArraySize)
      )
    }
    shiftedIndex -= objectArrayIndex.size
    require(index < primitiveArrayIndex.size)
    val objectId = primitiveArrayIndex.keyAt(shiftedIndex)
    val array = primitiveArrayIndex.getAtIndex(shiftedIndex)
    return objectId to IndexedPrimitiveArray(
        position = array.readTruncatedLong(positionSize),
        primitiveType = PrimitiveType.values()[array.readByte()
            .toInt()],
        recordSize = array.readTruncatedLong(bytesForPrimitiveArraySize)
    )
  }

  @Suppress("ReturnCount")
  fun indexedObjectOrNull(objectId: Long): IntObjectPair<IndexedObject>? {
    var index = classIndex.indexOf(objectId)
    if (index >= 0) {
      val array = classIndex.getAtIndex(index)
      return index to array.readClass()
    }
    index = instanceIndex.indexOf(objectId)
    if (index >= 0) {
      val array = instanceIndex.getAtIndex(index)
      return classIndex.size + index to IndexedInstance(
          position = array.readTruncatedLong(positionSize),
          classId = array.readId(),
          recordSize = array.readTruncatedLong(bytesForInstanceSize)
      )
    }
    index = objectArrayIndex.indexOf(objectId)
    if (index >= 0) {
      val array = objectArrayIndex.getAtIndex(index)
      return classIndex.size + instanceIndex.size + index to IndexedObjectArray(
          position = array.readTruncatedLong(positionSize),
          arrayClassId = array.readId(),
          recordSize = array.readTruncatedLong(bytesForObjectArraySize)
      )
    }
    index = primitiveArrayIndex.indexOf(objectId)
    if (index >= 0) {
      val array = primitiveArrayIndex.getAtIndex(index)
      return classIndex.size + instanceIndex.size + index + primitiveArrayIndex.size to IndexedPrimitiveArray(
          position = array.readTruncatedLong(positionSize),
          primitiveType = PrimitiveType.values()[array.readByte()
              .toInt()],
          recordSize = array.readTruncatedLong(bytesForPrimitiveArraySize)
      )
    }
    return null
  }

  private fun ByteSubArray.readClass(): IndexedClass {
    val position = readTruncatedLong(positionSize)
    val superclassId = readId()
    val instanceSize = readInt()

    val recordSize = readTruncatedLong(bytesForClassSize)
    val fieldsIndex = readTruncatedLong(classFieldsIndexSize).toInt()

    return IndexedClass(
        position = position,
        superclassId = superclassId,
        instanceSize = instanceSize,
        recordSize = recordSize,
        fieldsIndex = fieldsIndex
    )
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
    val bytesForClassSize: Int,
    val bytesForInstanceSize: Int,
    val bytesForObjectArraySize: Int,
    val bytesForPrimitiveArraySize: Int,
    val classFieldsTotalBytes: Int
  ) : OnHprofRecordTagListener {

    private val identifierSize = if (longIdentifiers) 8 else 4
    private val positionSize = byteSizeForUnsigned(maxPosition)
    private val classFieldsIndexSize = byteSizeForUnsigned(classFieldsTotalBytes.toLong())

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

    private val classFieldBytes = ByteArray(classFieldsTotalBytes)

    private var classFieldsIndex = 0

    private val classIndex = UnsortedByteEntries(
        bytesPerValue = positionSize + identifierSize + 4 + bytesForClassSize + classFieldsIndexSize,
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

    private val gcRoots = mutableListOf<GcRoot>()

    private fun HprofRecordReader.copyToClassFields(byteCount: Int) {
      for (i in 1..byteCount) {
        classFieldBytes[classFieldsIndex++] = readByte()
      }
    }

    private fun lastClassFieldsShort() =
      ((classFieldBytes[classFieldsIndex - 2].toInt() and 0xff shl 8) or
          (classFieldBytes[classFieldsIndex - 1].toInt() and 0xff)).toShort()

    @Suppress("LongMethod")
    override fun onHprofRecord(
      tag: HprofRecordTag,
      length: Long,
      reader: HprofRecordReader
    ) {
      when (tag) {
        STRING_IN_UTF8 -> {
          hprofStringCache[reader.readId()] = reader.readUtf8(length - identifierSize)
        }
        LOAD_CLASS -> {
          // classSerialNumber
          reader.skip(INT.byteSize)
          val id = reader.readId()
          // stackTraceSerialNumber
          reader.skip(INT.byteSize)
          val classNameStringId = reader.readId()
          classNames[id] = classNameStringId
        }
        ROOT_UNKNOWN -> {
          reader.readUnknownGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        ROOT_JNI_GLOBAL -> {
          reader.readJniGlobalGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        ROOT_JNI_LOCAL -> {
          reader.readJniLocalGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        ROOT_JAVA_FRAME -> {
          reader.readJavaFrameGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        ROOT_NATIVE_STACK -> {
          reader.readNativeStackGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        ROOT_STICKY_CLASS -> {
          reader.readStickyClassGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        ROOT_THREAD_BLOCK -> {
          reader.readThreadBlockGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        ROOT_MONITOR_USED -> {
          reader.readMonitorUsedGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        ROOT_THREAD_OBJECT -> {
          reader.readThreadObjectGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        ROOT_INTERNED_STRING -> {
          reader.readInternedStringGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        ROOT_FINALIZING -> {
          reader.readFinalizingGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        ROOT_DEBUGGER -> {
          reader.readDebuggerGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        ROOT_REFERENCE_CLEANUP -> {
          reader.readReferenceCleanupGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        ROOT_VM_INTERNAL -> {
          reader.readVmInternalGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        ROOT_JNI_MONITOR -> {
          reader.readJniMonitorGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        ROOT_UNREACHABLE -> {
          reader.readUnreachableGcRootRecord().apply {
            if (id != ValueHolder.NULL_REFERENCE) {
              gcRoots += this
            }
          }
        }
        CLASS_DUMP -> {
          val bytesReadStart = reader.bytesRead
          val id = reader.readId()
          // stack trace serial number
          reader.skip(INT.byteSize)
          val superclassId = reader.readId()
          reader.skip(5 * identifierSize)

          // instance size (in bytes)
          // Useful to compute retained size
          val instanceSize = reader.readInt()

          reader.skipClassDumpConstantPool()

          val startPosition = classFieldsIndex

          val bytesReadFieldStart = reader.bytesRead

          reader.copyToClassFields(2)
          val staticFieldCount = lastClassFieldsShort().toInt() and 0xFFFF
          for (i in 0 until staticFieldCount) {
            reader.copyToClassFields(identifierSize)
            reader.copyToClassFields(1)
            val type = classFieldBytes[classFieldsIndex - 1].toInt() and 0xff
            if (type == PrimitiveType.REFERENCE_HPROF_TYPE) {
              reader.copyToClassFields(identifierSize)
            } else {
              reader.copyToClassFields(PrimitiveType.byteSizeByHprofType.getValue(type))
            }
          }

          reader.copyToClassFields(2)
          val fieldCount = lastClassFieldsShort().toInt() and 0xFFFF
          for (i in 0 until fieldCount) {
            reader.copyToClassFields(identifierSize)
            reader.copyToClassFields(1)
          }

          val fieldsSize = (reader.bytesRead - bytesReadFieldStart).toInt()
          val recordSize = reader.bytesRead - bytesReadStart

          classIndex.append(id)
              .apply {
                writeTruncatedLong(bytesReadStart, positionSize)
                writeId(superclassId)
                writeInt(instanceSize)
                writeTruncatedLong(recordSize, bytesForClassSize)
                writeTruncatedLong(startPosition.toLong(), classFieldsIndexSize)
              }
          require(startPosition + fieldsSize == classFieldsIndex) {
            "Expected $classFieldsIndex to have moved by $fieldsSize and be equal to ${startPosition + fieldsSize}"
          }
        }
        INSTANCE_DUMP -> {
          val bytesReadStart = reader.bytesRead
          val id = reader.readId()
          reader.skip(INT.byteSize)
          val classId = reader.readId()
          val remainingBytesInInstance = reader.readInt()
          reader.skip(remainingBytesInInstance)
          val recordSize = reader.bytesRead - bytesReadStart
          instanceIndex.append(id)
              .apply {
                writeTruncatedLong(bytesReadStart, positionSize)
                writeId(classId)
                writeTruncatedLong(recordSize, bytesForInstanceSize)
              }
        }
        OBJECT_ARRAY_DUMP -> {
          val bytesReadStart = reader.bytesRead
          val id = reader.readId()
          reader.skip(INT.byteSize)
          val size = reader.readInt()
          val arrayClassId = reader.readId()
          reader.skip(identifierSize * size)
          val recordSize = reader.bytesRead - bytesReadStart
          objectArrayIndex.append(id)
              .apply {
                writeTruncatedLong(bytesReadStart, positionSize)
                writeId(arrayClassId)
                writeTruncatedLong(recordSize, bytesForObjectArraySize)
              }
        }
        PRIMITIVE_ARRAY_DUMP -> {
          val bytesReadStart = reader.bytesRead
          val id = reader.readId()
          reader.skip(INT.byteSize)
          val size = reader.readInt()
          val type = PrimitiveType.primitiveTypeByHprofType.getValue(reader.readUnsignedByte())
          reader.skip(size * type.byteSize)
          val recordSize = reader.bytesRead - bytesReadStart
          primitiveArrayIndex.append(id)
              .apply {
                writeTruncatedLong(bytesReadStart, positionSize)
                writeByte(type.ordinal.toByte())
                writeTruncatedLong(recordSize, bytesForPrimitiveArraySize)
              }
        }
      }
    }

    fun buildIndex(
      proguardMapping: ProguardMapping?,
      hprofHeader: HprofHeader
    ): HprofInMemoryIndex {
      require(classFieldsIndex == classFieldBytes.size) {
        "Read $classFieldsIndex into fields bytes instead of expected ${classFieldBytes.size}"
      }

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
          bytesForClassSize = bytesForClassSize,
          bytesForInstanceSize = bytesForInstanceSize,
          bytesForObjectArraySize = bytesForObjectArraySize,
          bytesForPrimitiveArraySize = bytesForPrimitiveArraySize,
          useForwardSlashClassPackageSeparator = hprofHeader.version != ANDROID,
          classFieldsReader = ClassFieldsReader(identifierSize, classFieldBytes),
          classFieldsIndexSize = classFieldsIndexSize
      )
    }

  }

  companion object {

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
      indexedGcRootTags: Set<HprofRecordTag>
    ): HprofInMemoryIndex {

      // First pass to count and correctly size arrays once and for all.
      var maxClassSize = 0L
      var maxInstanceSize = 0L
      var maxObjectArraySize = 0L
      var maxPrimitiveArraySize = 0L
      var classCount = 0
      var instanceCount = 0
      var objectArrayCount = 0
      var primitiveArrayCount = 0
      var classFieldsTotalBytes = 0

      val bytesRead = reader.readRecords(
          EnumSet.of(CLASS_DUMP, INSTANCE_DUMP, OBJECT_ARRAY_DUMP, PRIMITIVE_ARRAY_DUMP),
          OnHprofRecordTagListener { tag, _, reader ->
            val bytesReadStart = reader.bytesRead
            when (tag) {
              CLASS_DUMP -> {
                classCount++
                reader.skipClassDumpHeader()
                val bytesReadStaticFieldStart = reader.bytesRead
                reader.skipClassDumpStaticFields()
                reader.skipClassDumpFields()
                maxClassSize = max(maxClassSize, reader.bytesRead - bytesReadStart)
                classFieldsTotalBytes += (reader.bytesRead - bytesReadStaticFieldStart).toInt()
              }
              INSTANCE_DUMP -> {
                instanceCount++
                reader.skipInstanceDumpRecord()
                maxInstanceSize = max(maxInstanceSize, reader.bytesRead - bytesReadStart)
              }
              OBJECT_ARRAY_DUMP -> {
                objectArrayCount++
                reader.skipObjectArrayDumpRecord()
                maxObjectArraySize = max(maxObjectArraySize, reader.bytesRead - bytesReadStart)
              }
              PRIMITIVE_ARRAY_DUMP -> {
                primitiveArrayCount++
                reader.skipPrimitiveArrayDumpRecord()
                maxPrimitiveArraySize = max(maxPrimitiveArraySize, reader.bytesRead - bytesReadStart)
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
          bytesForClassSize = bytesForClassSize,
          bytesForInstanceSize = bytesForInstanceSize,
          bytesForObjectArraySize = bytesForObjectArraySize,
          bytesForPrimitiveArraySize = bytesForPrimitiveArraySize,
          classFieldsTotalBytes = classFieldsTotalBytes
      )

      val recordTypes = EnumSet.of(
          STRING_IN_UTF8,
          LOAD_CLASS,
          CLASS_DUMP,
          INSTANCE_DUMP,
          OBJECT_ARRAY_DUMP,
          PRIMITIVE_ARRAY_DUMP
      ) + HprofRecordTag.rootTags.intersect(indexedGcRootTags)

      reader.readRecords(recordTypes, indexBuilderListener)
      return indexBuilderListener.buildIndex(proguardMapping, hprofHeader)
    }
  }
}