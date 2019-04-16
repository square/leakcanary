package leakcanary.internal.haha

import leakcanary.internal.haha.GcRoot.Debugger
import leakcanary.internal.haha.GcRoot.Finalizing
import leakcanary.internal.haha.GcRoot.InternedString
import leakcanary.internal.haha.GcRoot.JavaFrame
import leakcanary.internal.haha.GcRoot.JniGlobal
import leakcanary.internal.haha.GcRoot.JniLocal
import leakcanary.internal.haha.GcRoot.JniMonitor
import leakcanary.internal.haha.GcRoot.MonitorUsed
import leakcanary.internal.haha.GcRoot.NativeStack
import leakcanary.internal.haha.GcRoot.ReferenceCleanup
import leakcanary.internal.haha.GcRoot.StickyClass
import leakcanary.internal.haha.GcRoot.ThreadBlock
import leakcanary.internal.haha.GcRoot.ThreadObject
import leakcanary.internal.haha.GcRoot.Unknown
import leakcanary.internal.haha.GcRoot.Unreachable
import leakcanary.internal.haha.GcRoot.VmInternal
import leakcanary.internal.haha.HeapValue.IntValue
import leakcanary.internal.haha.HeapValue.ObjectReference
import leakcanary.internal.haha.HprofReader.Companion.BYTE_SIZE
import leakcanary.internal.haha.HprofReader.Companion.INT_SIZE
import leakcanary.internal.haha.HprofReader.Companion.LONG_SIZE
import leakcanary.internal.haha.HprofReader.Companion.SHORT_SIZE
import leakcanary.internal.haha.Record.HeapDumpRecord.GcRootRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.HeapDumpInfoRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.ObjectRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import leakcanary.internal.haha.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import leakcanary.internal.haha.Record.LoadClassRecord
import leakcanary.internal.haha.Record.StringRecord
import okio.Buffer
import okio.buffer
import okio.source
import java.io.Closeable
import java.io.File
import java.nio.charset.Charset

/**
 * Not thread safe, should be used from a single thread.
 */
class HprofParser private constructor(
  private val reader: SeekableHprofReader
) : Closeable {

  private var scanning = false
  private var indexBuilt = false

  /**
   * string id to (position, length)
   * TODO Prune any string that's not used as a class name or field name
   */
  private val hprofStringPositions = mutableMapOf<Long, Pair<Long, Long>>()

  /**
   * class id to string id
   */
  private val classNames = mutableMapOf<Long, Long>()

  /**
   * Object id to object position. The id can be for classes instances, classes, object arrays
   * and primitive arrays
   */
  private val objectPositions = mutableMapOf<Long, Long>()

  class RecordCallbacks {
    private val callbacks = mutableMapOf<Class<out Record>, Any>()

    fun <T : Record> on(
      recordClass: Class<T>,
      callback: (T) -> Unit
    ): RecordCallbacks {
      callbacks[recordClass] = callback
      return this
    }

    fun <T : Record> get(recordClass: Class<T>): ((T) -> Unit)? {
      @Suppress("UNCHECKED_CAST")
      return callbacks[recordClass] as ((T) -> Unit)?
    }

    inline fun <reified T : Record> get(): ((T) -> Unit)? {
      return get(T::class.java)
    }
  }

  override fun close() {
    reader.close()
  }

  fun scan(callbacks: RecordCallbacks) {
    reader.scan(callbacks)
  }

  private fun SeekableHprofReader.scan(callbacks: RecordCallbacks) {
    if (!isOpen) {
      throw IllegalStateException("Reader closed")
    }

    if (scanning) {
      throw UnsupportedOperationException("Cannot scan while already scanning.")
    }

    scanning = true

    reset()

    // heap dump timestamp
    skip(LONG_SIZE)

    var heapId = 0
    while (!exhausted()) {
      // type of the record
      val tag = readUnsignedByte()

      // number of microseconds since the time stamp in the header
      skip(INT_SIZE)

      // number of bytes that follow and belong to this record
      val length = readUnsignedInt()

      when (tag) {
        STRING_IN_UTF8 -> {
          val callback = callbacks.get<StringRecord>()
          if (callback != null || !indexBuilt) {
            val id = readId()
            if (!indexBuilt) {
              hprofStringPositions[id] = position to length - idSize
            }
            if (callback != null) {
              val string = readUtf8(length - idSize)
              callback(StringRecord(id, string))
            } else {
              skip(length - idSize)
            }
          } else {
            skip(length)
          }
        }
        LOAD_CLASS -> {
          val callback = callbacks.get<LoadClassRecord>()
          if (callback != null || !indexBuilt) {
            val classSerialNumber = readInt()
            val id = readId()
            val stackTraceSerialNumber = readInt()
            val classNameStringId = readId()
            if (!indexBuilt) {
              classNames[id] = classNameStringId
            }
            if (callback != null) {
              callback(
                  LoadClassRecord(
                      classSerialNumber = classSerialNumber,
                      id = id,
                      stackTraceSerialNumber = stackTraceSerialNumber,
                      classNameStringId = classNameStringId
                  )
              )
            }
          } else {
            skip(length)
          }
        }
        HEAP_DUMP, HEAP_DUMP_SEGMENT -> {
          val heapDumpStart = position
          var previousTag = 0
          while (position - heapDumpStart < length) {
            val heapDumpTag = readUnsignedByte()

            when (heapDumpTag) {
              ROOT_UNKNOWN -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = Unknown(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              ROOT_JNI_GLOBAL -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = JniGlobal(id = readId(), jniGlobalRefId = readId())
                      )
                  )
                } else {
                  skip(idSize + idSize)
                }
              }

              ROOT_JNI_LOCAL -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = JniLocal(
                              id = readId(), threadSerialNumber = readInt(), frameNumber = readInt()
                          )
                      )
                  )
                } else {
                  skip(idSize + INT_SIZE + INT_SIZE)
                }
              }

              ROOT_JAVA_FRAME -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = JavaFrame(
                              id = readId(), threadSerialNumber = readInt(), frameNumber = readInt()
                          )
                      )
                  )
                } else {
                  skip(idSize + INT_SIZE + INT_SIZE)
                }
              }

              ROOT_NATIVE_STACK -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = NativeStack(id = readId(), threadSerialNumber = readInt())
                      )
                  )
                } else {
                  skip(idSize + INT_SIZE)
                }
              }

              ROOT_STICKY_CLASS -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = StickyClass(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              // An object that was referenced from an active thread block.
              ROOT_THREAD_BLOCK -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = ThreadBlock(id = readId(), threadSerialNumber = readInt())
                      )
                  )
                } else {
                  skip(idSize + INT_SIZE)
                }
              }

              ROOT_MONITOR_USED -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = MonitorUsed(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              ROOT_THREAD_OBJECT -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = ThreadObject(
                              id = readId(),
                              threadSerialNumber = readInt(),
                              stackTraceSerialNumber = readInt()
                          )
                      )
                  )
                } else {
                  skip(idSize + INT_SIZE + INT_SIZE)
                }
              }

              CLASS_DUMP -> {
                val callback = callbacks.get<ClassDumpRecord>()
                val id = readId()
                if (!indexBuilt) {
                  objectPositions[id] = tagPositionAfterReadingId
                }
                if (callback != null) {
                  val classDumpRecord = readClassDumpRecord(id)
                  callback(classDumpRecord)
                } else {
                  skip(
                      INT_SIZE + idSize + idSize + idSize + idSize + idSize + idSize + INT_SIZE
                  )

                  // Skip over the constant pool
                  val constantPoolCount = readUnsignedShort()
                  for (i in 0 until constantPoolCount) {
                    // constant pool index
                    skip(SHORT_SIZE)
                    skip(typeSize(readUnsignedByte()))
                  }

                  val staticFieldCount = readUnsignedShort()

                  for (i in 0 until staticFieldCount) {
                    skip(idSize)
                    val type = readUnsignedByte()
                    skip(typeSize(type))
                  }

                  val fieldCount = readUnsignedShort()
                  skip(fieldCount * (idSize + BYTE_SIZE))
                }
              }

              INSTANCE_DUMP -> {
                val id = readId()
                if (!indexBuilt) {
                  objectPositions[id] = tagPositionAfterReadingId
                }
                val callback = callbacks.get<InstanceDumpRecord>()
                if (callback != null) {
                  val instanceDumpRecord = readInstanceDumpRecord(id)
                  callback(instanceDumpRecord)
                } else {
                  skip(INT_SIZE + idSize)
                  val remainingBytesInInstance = readInt()
                  skip(remainingBytesInInstance)
                }
              }

              OBJECT_ARRAY_DUMP -> {
                val id = readId()
                if (!indexBuilt) {
                  objectPositions[id] = tagPositionAfterReadingId
                }
                val callback = callbacks.get<ObjectArrayDumpRecord>()
                if (callback != null) {
                  callback(readObjectArrayDumpRecord(id))
                } else {
                  skip(INT_SIZE)
                  val arrayLength = readInt()
                  skip(idSize + arrayLength * idSize)
                }
              }

              PRIMITIVE_ARRAY_DUMP -> {
                val id = readId()
                if (!indexBuilt) {
                  objectPositions[id] = tagPositionAfterReadingId
                }
                val callback = callbacks.get<PrimitiveArrayDumpRecord>()
                if (callback != null) {
                  callback(readPrimitiveArrayDumpRecord(id))
                } else {
                  skip(INT_SIZE)
                  val arrayLength = readInt()
                  val type = readUnsignedByte()
                  skip(arrayLength * typeSize(type))
                }
              }

              PRIMITIVE_ARRAY_NODATA -> {
                throw UnsupportedOperationException(
                    "PRIMITIVE_ARRAY_NODATA cannot be parsed"
                )
              }

              HEAP_DUMP_INFO -> {
                heapId = readInt()
                val callback = callbacks.get<HeapDumpInfoRecord>()
                if (callback != null) {
                  val record =
                    HeapDumpInfoRecord(heapId = heapId, heapNameStringId = readId())
                  callback(record)
                } else {
                  skip(idSize)
                }

              }

              ROOT_INTERNED_STRING -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = InternedString(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              ROOT_FINALIZING -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = Finalizing(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              ROOT_DEBUGGER -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = Debugger(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              ROOT_REFERENCE_CLEANUP -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = ReferenceCleanup(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              ROOT_VM_INTERNAL -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = VmInternal(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              ROOT_JNI_MONITOR -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = JniMonitor(
                              id = readId(), stackTraceSerialNumber = readInt(),
                              stackDepth = readInt()
                          )
                      )
                  )
                } else {
                  skip(idSize + INT_SIZE + INT_SIZE)
                }
              }

              ROOT_UNREACHABLE -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          gcRoot = Unreachable(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }
              else -> throw IllegalStateException(
                  "Unknown tag $heapDumpTag after $previousTag"
              )
            }
            previousTag = heapDumpTag
          }
          heapId = 0
        }
        else -> {
          skip(length)
        }
      }
    }

    scanning = false
    indexBuilt = true
  }

  /**
   * Those are strings for class names, fields, etc, ie not strings from the application memory.
   */
  fun hprofStringById(id: Long): String {
    val (position, length) = hprofStringPositions[id]!!
    reader.moveTo(position)
    return reader.readUtf8(length)
  }

  fun retrieveString(reference: ObjectReference): String {
    val instanceRecord = retrieveRecord(reference) as InstanceDumpRecord
    val instance = hydrateInstance(instanceRecord)
    return instanceAsString(instance)
  }

  fun retrieveRecord(reference: ObjectReference): ObjectRecord {
    return retrieveRecord(reference.value)
  }

  fun retrieveRecord(objectId: Long): ObjectRecord {
    val position = objectPositions[objectId]
    require(position != null) {
      "Unknown object id $objectId"
    }
    reader.moveTo(position)
    val heapDumpTag = reader.readUnsignedByte()

    reader.skip(reader.idSize)
    return when (heapDumpTag) {
      CLASS_DUMP -> reader.readClassDumpRecord(objectId)
      INSTANCE_DUMP -> reader.readInstanceDumpRecord(objectId)
      OBJECT_ARRAY_DUMP -> reader.readObjectArrayDumpRecord(objectId)
      PRIMITIVE_ARRAY_DUMP -> reader.readPrimitiveArrayDumpRecord(objectId)
      else -> {
        throw IllegalStateException(
            "Unexpected tag $heapDumpTag for id $objectId at position $position"
        )
      }
    }
  }

  data class HydratedInstance(
    val record: InstanceDumpRecord,
    val classHierarchy: List<HydratedClass>,
    /**
     * One list of field values per class
     */
    val fieldValues: List<List<HeapValue>>
  ) {
    fun <T : HeapValue> fieldValue(name: String): T {
      return fieldValueOrNull(name) ?: throw IllegalArgumentException(
          "Could not find field $name in instance with id ${record.id}"
      )
    }

    fun <T : HeapValue> fieldValueOrNull(name: String): T? {
      classHierarchy.forEachIndexed { classIndex, hydratedClass ->
        hydratedClass.fieldNames.forEachIndexed { fieldIndex, fieldName ->
          if (fieldName == name) {
            @Suppress("UNCHECKED_CAST")
            return fieldValues[classIndex][fieldIndex] as T
          }
        }
      }
      return null
    }
  }

  data class HydratedClass(
    val record: ClassDumpRecord,
    val className: String,
    val staticFieldNames: List<String>,
    val fieldNames: List<String>
  )

  fun hydrateInstance(instanceRecord: InstanceDumpRecord): HydratedInstance {
    var classId = instanceRecord.classId

    val classHierarchy = mutableListOf<HydratedClass>()
    do {
      val classRecord = retrieveRecord(classId) as ClassDumpRecord
      val className = hprofStringById(classNames[classRecord.id]!!)

      val staticFieldNames = classRecord.staticFields.map {
        hprofStringById(it.nameStringId)
      }

      val fieldNames = classRecord.fields.map {
        hprofStringById(it.nameStringId)
      }

      classHierarchy.add(HydratedClass(classRecord, className, staticFieldNames, fieldNames))
      classId = classRecord.superClassId
    } while (classId != 0L)

    val buffer = Buffer()
    buffer.write(instanceRecord.fieldValues)
    val valuesReader = HprofReader(buffer, 0, reader.idSize)

    val allFieldValues = classHierarchy.map { hydratedClass ->
      hydratedClass.record.fields.map { field -> valuesReader.readValue(field.type) }
    }

    return HydratedInstance(instanceRecord, classHierarchy, allFieldValues)
  }

  fun instanceAsString(instance: HydratedInstance): String {
    val count = instance.fieldValue<IntValue>("count")
        .value

    if (count == 0) {
      return ""
    }

    val value = instance.fieldValue<ObjectReference>("value")
    val valueRecord = retrieveRecord(value)

    when (valueRecord) {
      is CharArrayDump -> {
        var offset = 0

        // < API 23
        // As of Marshmallow, substrings no longer share their parent strings' char arrays
        // eliminating the need for String.offset
        // https://android-review.googlesource.com/#/c/83611/
        val offsetValue = instance.fieldValueOrNull<IntValue>("offset")
        if (offsetValue != null) {
          offset = offsetValue.value
        }

        val chars = valueRecord.array.copyOfRange(offset, offset + count)
        return String(chars)
      }
      is ByteArrayDump -> {
        return String(valueRecord.array, Charset.forName("UTF-8"))
      }
      else -> throw UnsupportedOperationException(
          "'value' field was expected to be either a char or byte array in string instance with id ${instance.record.id}"
      )
    }
  }

  companion object {
    const val STRING_IN_UTF8 = 0x01
    const val LOAD_CLASS = 0x02
    const val UNLOAD_CLASS = 0x03
    const val STACK_FRAME = 0x04
    const val STACK_TRACE = 0x05
    const val ALLOC_SITES = 0x06
    const val HEAP_SUMMARY = 0x07
    const val START_THREAD = 0x0a
    const val END_THREAD = 0x0b
    const val HEAP_DUMP = 0x0c
    const val HEAP_DUMP_SEGMENT = 0x1c

    const val HEAP_DUMP_END = 0x2c

    const val CPU_SAMPLES = 0x0d

    const val CONTROL_SETTINGS = 0x0e

    const val ROOT_UNKNOWN = 0xff

    const val ROOT_JNI_GLOBAL = 0x01

    const val ROOT_JNI_LOCAL = 0x02

    const val ROOT_JAVA_FRAME = 0x03

    const val ROOT_NATIVE_STACK = 0x04

    const val ROOT_STICKY_CLASS = 0x05

    const val ROOT_THREAD_BLOCK = 0x06

    const val ROOT_MONITOR_USED = 0x07

    const val ROOT_THREAD_OBJECT = 0x08

    const val CLASS_DUMP = 0x20

    const val INSTANCE_DUMP = 0x21

    const val OBJECT_ARRAY_DUMP = 0x22

    const val PRIMITIVE_ARRAY_DUMP = 0x23

    /**
     * Android format addition
     *
     * Specifies information about which heap certain objects came from. When a sub-tag of this type
     * appears in a HPROF_HEAP_DUMP or HPROF_HEAP_DUMP_SEGMENT record, entries that follow it will
     * be associated with the specified heap.  The HEAP_DUMP_INFO data is reset at the end of the
     * HEAP_DUMP[_SEGMENT].  Multiple HEAP_DUMP_INFO entries may appear in a single
     * HEAP_DUMP[_SEGMENT].
     *
     * Format: u1: Tag value (0xFE) u4: heap ID ID: heap name string ID
     */
    const val HEAP_DUMP_INFO = 0xfe

    const val ROOT_INTERNED_STRING = 0x89

    const val ROOT_FINALIZING = 0x8a

    const val ROOT_DEBUGGER = 0x8b

    const val ROOT_REFERENCE_CLEANUP = 0x8c

    const val ROOT_VM_INTERNAL = 0x8d

    const val ROOT_JNI_MONITOR = 0x8e

    const val ROOT_UNREACHABLE = 0x90

    const val PRIMITIVE_ARRAY_NODATA = 0xc3

    fun open(heapDump: File): HprofParser {
      val inputStream = heapDump.inputStream()
      val channel = inputStream.channel
      val source = inputStream.source()
          .buffer()

      val endOfVersionString = source.indexOf(0)
      source.skip(endOfVersionString + 1)
      val idSize = source.readInt()
      val startPosition = endOfVersionString + 1 + 4

      val hprofReader = SeekableHprofReader(channel, source, startPosition, idSize)
      return HprofParser(hprofReader)
    }
  }

}