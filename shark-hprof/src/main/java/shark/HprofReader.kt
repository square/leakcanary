package shark

import okio.BufferedSource
import shark.GcRoot.Debugger
import shark.GcRoot.Finalizing
import shark.GcRoot.InternedString
import shark.GcRoot.JavaFrame
import shark.GcRoot.JniGlobal
import shark.GcRoot.JniLocal
import shark.GcRoot.JniMonitor
import shark.GcRoot.MonitorUsed
import shark.GcRoot.NativeStack
import shark.GcRoot.ReferenceCleanup
import shark.GcRoot.StickyClass
import shark.GcRoot.ThreadBlock
import shark.GcRoot.ThreadObject
import shark.GcRoot.Unknown
import shark.GcRoot.Unreachable
import shark.GcRoot.VmInternal
import shark.HprofRecord.HeapDumpEndRecord
import shark.HprofRecord.HeapDumpRecord
import shark.HprofRecord.HeapDumpRecord.GcRootRecord
import shark.HprofRecord.HeapDumpRecord.HeapDumpInfoRecord
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
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StackFrameRecord
import shark.HprofRecord.StackTraceRecord
import shark.HprofRecord.StringRecord
import shark.PrimitiveType.BOOLEAN
import shark.PrimitiveType.BYTE
import shark.PrimitiveType.CHAR
import shark.PrimitiveType.DOUBLE
import shark.PrimitiveType.FLOAT
import shark.PrimitiveType.INT
import shark.PrimitiveType.LONG
import shark.PrimitiveType.SHORT
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.ByteHolder
import shark.ValueHolder.CharHolder
import shark.ValueHolder.DoubleHolder
import shark.ValueHolder.FloatHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder
import shark.ValueHolder.ShortHolder
import java.nio.charset.Charset
import kotlin.reflect.KClass

/**
 * Reads hprof content from an Okio [BufferedSource].
 *
 * Not thread safe, should be used from a single thread.
 *
 * Binary Dump Format reference: http://hg.openjdk.java.net/jdk6/jdk6/jdk/raw-file/tip/src/share/demo/jvmti/hprof/manual.html#mozTocId848088
 *
 * The Android Hprof format differs in some ways from that reference. This parser implementation
 * is largely adapted from https://android.googlesource.com/platform/tools/base/+/studio-master-dev/perflib/src/main/java/com/android/tools/perflib
 */
class HprofReader constructor(
  private var source: BufferedSource,
  /**
   * Size of Hprof identifiers. Identifiers are used to represent UTF8 strings, objects,
   * stack traces, etc. They can have the same size as host pointers or sizeof(void*), but are not
   * required to be.
   */
  val identifierByteSize: Int,
  /**
   * How many bytes have already been read from [source] when this [HprofReader] is created.
   */
  val startByteReadCount: Long = 0L
) {

  /**
   * Starts at [startByteReadCount] and increases as [HprofReader] reads bytes. This is useful
   * for tracking the position of content in the backing [source]. This never resets.
   */
  var byteReadCount = startByteReadCount
    private set

  private val typeSizes =
    PrimitiveType.byteSizeByHprofType + (PrimitiveType.REFERENCE_HPROF_TYPE to identifierByteSize)

  /**
   * Reads all hprof records from [source].
   * Assumes the [reader] was just created, with a source that currently points to the start
   * position of hprof records.
   */
  fun readHprofRecords(
    recordTypes: Set<KClass<out HprofRecord>>,
    listener: OnHprofRecordListener
  ) {
    require(byteReadCount == startByteReadCount) {
      "readHprofRecords() should only be called on a unused HprofReader instance"
    }
    val readAllRecords = HprofRecord::class in recordTypes
    val readStringRecord = readAllRecords || StringRecord::class in recordTypes
    val readLoadClassRecord = readAllRecords || LoadClassRecord::class in recordTypes
    val readHeapDumpEndRecord = readAllRecords || HeapDumpEndRecord::class in recordTypes
    val readStackFrameRecord = readAllRecords || StackFrameRecord::class in recordTypes
    val readStackTraceRecord = readAllRecords || StackTraceRecord::class in recordTypes

    val readAllHeapDumpRecords = readAllRecords || HeapDumpRecord::class in recordTypes

    val readGcRootRecord = readAllHeapDumpRecords || GcRootRecord::class in recordTypes
    val readHeapDumpInfoRecord = readAllRecords || HeapDumpInfoRecord::class in recordTypes

    val readAllObjectRecords = readAllHeapDumpRecords || ObjectRecord::class in recordTypes

    val readClassDumpRecord = readAllObjectRecords || ClassDumpRecord::class in recordTypes
    val readInstanceDumpRecord = readAllObjectRecords || InstanceDumpRecord::class in recordTypes
    val readObjectArrayDumpRecord =
      readAllObjectRecords || ObjectArrayDumpRecord::class in recordTypes
    val readPrimitiveArrayDumpRecord =
      readAllObjectRecords || PrimitiveArrayDumpRecord::class in recordTypes

    val intByteSize = INT.byteSize

    while (!exhausted()) {
      // type of the record
      val tag = readUnsignedByte()

      // number of microseconds since the time stamp in the header
      skip(intByteSize)

      // number of bytes that follow and belong to this record
      val length = readUnsignedInt()

      when (tag) {
        STRING_IN_UTF8 -> {
          if (readStringRecord) {
            val recordPosition = byteReadCount
            val id = readId()
            val stringLength = length - identifierByteSize
            val string = readUtf8(stringLength)
            val record = StringRecord(id, string)
            listener.onHprofRecord(recordPosition, record)
          } else {
            skip(length)
          }
        }
        LOAD_CLASS -> {
          if (readLoadClassRecord) {
            val recordPosition = byteReadCount
            val classSerialNumber = readInt()
            val id = readId()
            val stackTraceSerialNumber = readInt()
            val classNameStringId = readId()
            val record = LoadClassRecord(
                classSerialNumber = classSerialNumber,
                id = id,
                stackTraceSerialNumber = stackTraceSerialNumber,
                classNameStringId = classNameStringId
            )
            listener.onHprofRecord(recordPosition, record)
          } else {
            skip(length)
          }
        }
        STACK_FRAME -> {
          if (readStackFrameRecord) {
            val recordPosition = byteReadCount
            val record = StackFrameRecord(
                id = readId(),
                methodNameStringId = readId(),
                methodSignatureStringId = readId(),
                sourceFileNameStringId = readId(),
                classSerialNumber = readInt(),
                lineNumber = readInt()
            )
            listener.onHprofRecord(recordPosition, record)
          } else {
            skip(length)
          }
        }
        STACK_TRACE -> {
          if (readStackTraceRecord) {
            val recordPosition = byteReadCount
            val stackTraceSerialNumber = readInt()
            val threadSerialNumber = readInt()
            val frameCount = readInt()
            val stackFrameIds = readIdArray(frameCount)
            val record = StackTraceRecord(
                stackTraceSerialNumber = stackTraceSerialNumber,
                threadSerialNumber = threadSerialNumber,
                stackFrameIds = stackFrameIds
            )
            listener.onHprofRecord(recordPosition, record)
          } else {
            skip(length)
          }
        }
        HEAP_DUMP, HEAP_DUMP_SEGMENT -> {
          val heapDumpStart = byteReadCount
          var previousTag = 0
          while (byteReadCount - heapDumpStart < length) {
            val heapDumpTag = readUnsignedByte()

            when (heapDumpTag) {
              ROOT_UNKNOWN -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val record = GcRootRecord(gcRoot = Unknown(id = readId()))
                  listener.onHprofRecord(recordPosition, record)
                } else {
                  skip(identifierByteSize)
                }
              }
              ROOT_JNI_GLOBAL -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val gcRootRecord =
                    GcRootRecord(gcRoot = JniGlobal(id = readId(), jniGlobalRefId = readId()))
                  listener.onHprofRecord(recordPosition, gcRootRecord)
                } else {
                  skip(identifierByteSize + identifierByteSize)
                }
              }

              ROOT_JNI_LOCAL -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val gcRootRecord = GcRootRecord(
                      gcRoot = JniLocal(
                          id = readId(), threadSerialNumber = readInt(), frameNumber = readInt()
                      )
                  )
                  listener.onHprofRecord(recordPosition, gcRootRecord)
                } else {
                  skip(identifierByteSize + intByteSize + intByteSize)
                }
              }

              ROOT_JAVA_FRAME -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val gcRootRecord = GcRootRecord(
                      gcRoot = JavaFrame(
                          id = readId(), threadSerialNumber = readInt(), frameNumber = readInt()
                      )
                  )
                  listener.onHprofRecord(recordPosition, gcRootRecord)
                } else {
                  skip(identifierByteSize + intByteSize + intByteSize)
                }
              }

              ROOT_NATIVE_STACK -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val gcRootRecord = GcRootRecord(
                      gcRoot = NativeStack(id = readId(), threadSerialNumber = readInt())
                  )
                  listener.onHprofRecord(recordPosition, gcRootRecord)
                } else {
                  skip(identifierByteSize + intByteSize)
                }
              }

              ROOT_STICKY_CLASS -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val gcRootRecord = GcRootRecord(
                      gcRoot = StickyClass(id = readId())
                  )
                  listener.onHprofRecord(recordPosition, gcRootRecord)
                } else {
                  skip(identifierByteSize)
                }
              }

              // An object that was referenced from an active thread block.
              ROOT_THREAD_BLOCK -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val gcRootRecord = GcRootRecord(
                      gcRoot = ThreadBlock(id = readId(), threadSerialNumber = readInt())
                  )
                  listener.onHprofRecord(recordPosition, gcRootRecord)
                } else {
                  skip(identifierByteSize + intByteSize)
                }
              }

              ROOT_MONITOR_USED -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val gcRootRecord = GcRootRecord(
                      gcRoot = MonitorUsed(id = readId())
                  )
                  listener.onHprofRecord(recordPosition, gcRootRecord)
                } else {
                  skip(identifierByteSize)
                }
              }

              ROOT_THREAD_OBJECT -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val gcRootRecord = GcRootRecord(
                      gcRoot = ThreadObject(
                          id = readId(),
                          threadSerialNumber = readInt(),
                          stackTraceSerialNumber = readInt()
                      )
                  )
                  listener.onHprofRecord(recordPosition, gcRootRecord)
                } else {
                  skip(identifierByteSize + intByteSize + intByteSize)
                }
              }

              ROOT_INTERNED_STRING -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val gcRootRecord = GcRootRecord(gcRoot = InternedString(id = readId()))
                  listener.onHprofRecord(recordPosition, gcRootRecord)
                } else {
                  skip(identifierByteSize)
                }
              }

              ROOT_FINALIZING -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val gcRootRecord = GcRootRecord(
                      gcRoot = Finalizing(id = readId())
                  )
                  listener.onHprofRecord(recordPosition, gcRootRecord)
                } else {
                  skip(identifierByteSize)
                }
              }

              ROOT_DEBUGGER -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val gcRootRecord = GcRootRecord(
                      gcRoot = Debugger(id = readId())
                  )
                  listener.onHprofRecord(recordPosition, gcRootRecord)
                } else {
                  skip(identifierByteSize)
                }
              }

              ROOT_REFERENCE_CLEANUP -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val gcRootRecord = GcRootRecord(
                      gcRoot = ReferenceCleanup(id = readId())
                  )
                  listener.onHprofRecord(recordPosition, gcRootRecord)
                } else {
                  skip(identifierByteSize)
                }
              }

              ROOT_VM_INTERNAL -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val gcRootRecord = GcRootRecord(
                      gcRoot = VmInternal(id = readId())
                  )
                  listener.onHprofRecord(recordPosition, gcRootRecord)
                } else {
                  skip(identifierByteSize)
                }
              }

              ROOT_JNI_MONITOR -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val gcRootRecord = GcRootRecord(
                      gcRoot = JniMonitor(
                          id = readId(), stackTraceSerialNumber = readInt(),
                          stackDepth = readInt()
                      )
                  )
                  listener.onHprofRecord(recordPosition, gcRootRecord)
                } else {
                  skip(identifierByteSize + intByteSize + intByteSize)
                }
              }

              ROOT_UNREACHABLE -> {
                if (readGcRootRecord) {
                  val recordPosition = byteReadCount
                  val gcRootRecord = GcRootRecord(
                      gcRoot = Unreachable(id = readId())
                  )
                  listener.onHprofRecord(recordPosition, gcRootRecord)
                } else {
                  skip(identifierByteSize)
                }
              }
              CLASS_DUMP -> {
                if (readClassDumpRecord) {
                  val recordPosition = byteReadCount
                  val record = readClassDumpRecord()
                  listener.onHprofRecord(recordPosition, record)
                } else {
                  skipClassDumpRecord()
                }
              }

              INSTANCE_DUMP -> {
                if (readInstanceDumpRecord) {
                  val recordPosition = byteReadCount
                  val instanceDumpRecord = readInstanceDumpRecord()
                  listener.onHprofRecord(recordPosition, instanceDumpRecord)
                } else {
                  skipInstanceDumpRecord()
                }
              }

              OBJECT_ARRAY_DUMP -> {
                if (readObjectArrayDumpRecord) {
                  val recordPosition = byteReadCount
                  val arrayRecord = readObjectArrayDumpRecord()
                  listener.onHprofRecord(recordPosition, arrayRecord)
                } else {
                  skipObjectArrayDumpRecord()
                }
              }

              PRIMITIVE_ARRAY_DUMP -> {
                if (readPrimitiveArrayDumpRecord) {
                  val recordPosition = byteReadCount
                  val record = readPrimitiveArrayDumpRecord()
                  listener.onHprofRecord(recordPosition, record)
                } else {
                  skipPrimitiveArrayDumpRecord()
                }
              }

              PRIMITIVE_ARRAY_NODATA -> {
                throw UnsupportedOperationException("PRIMITIVE_ARRAY_NODATA cannot be parsed")
              }

              HEAP_DUMP_INFO -> {
                if (readHeapDumpInfoRecord) {
                  val recordPosition = byteReadCount
                  val record = readHeapDumpInfoRecord()
                  listener.onHprofRecord(recordPosition, record)
                } else {
                  skipHeapDumpInfoRecord()
                }
              }
              else -> throw IllegalStateException(
                  "Unknown tag $heapDumpTag after $previousTag"
              )
            }
            previousTag = heapDumpTag
          }
        }
        HEAP_DUMP_END -> {
          if (readHeapDumpEndRecord) {
            val recordPosition = byteReadCount
            val record = HeapDumpEndRecord
            listener.onHprofRecord(recordPosition, record)
          }
        }
        else -> {
          skip(length)
        }
      }
    }
  }

  /**
   * Reads a full instance record after a instance dump tag.
   */
  fun readInstanceDumpRecord(): InstanceDumpRecord {
    val id = readId()
    val stackTraceSerialNumber = readInt()
    val classId = readId()
    val remainingBytesInInstance = readInt()
    val fieldValues = readByteArray(remainingBytesInInstance)
    return InstanceDumpRecord(
        id = id,
        stackTraceSerialNumber = stackTraceSerialNumber,
        classId = classId,
        fieldValues = fieldValues
    )
  }

  /**
   * Reads a full class record after a class dump tag.
   */
  fun readClassDumpRecord(): ClassDumpRecord {
    val id = readId()
    // stack trace serial number
    val stackTraceSerialNumber = readInt()
    val superclassId = readId()
    // class loader object ID
    val classLoaderId = readId()
    // signers object ID
    val signersId = readId()
    // protection domain object ID
    val protectionDomainId = readId()
    // reserved
    readId()
    // reserved
    readId()

    // instance size (in bytes)
    // Useful to compute retained size
    val instanceSize = readInt()

    // Skip over the constant pool
    val constantPoolCount = readUnsignedShort()
    for (i in 0 until constantPoolCount) {
      // constant pool index
      skip(SHORT_SIZE)
      skip(typeSize(readUnsignedByte()))
    }

    val staticFieldCount = readUnsignedShort()
    val staticFields = ArrayList<StaticFieldRecord>(staticFieldCount)
    for (i in 0 until staticFieldCount) {

      val nameStringId = readId()
      val type = readUnsignedByte()
      val value = readValue(type)

      staticFields.add(
          StaticFieldRecord(
              nameStringId = nameStringId,
              type = type,
              value = value
          )
      )
    }

    val fieldCount = readUnsignedShort()
    val fields = ArrayList<FieldRecord>(fieldCount)
    for (i in 0 until fieldCount) {
      fields.add(FieldRecord(nameStringId = readId(), type = readUnsignedByte()))
    }

    return ClassDumpRecord(
        id = id,
        stackTraceSerialNumber = stackTraceSerialNumber,
        superclassId = superclassId,
        classLoaderId = classLoaderId,
        signersId = signersId,
        protectionDomainId = protectionDomainId,
        instanceSize = instanceSize,
        staticFields = staticFields,
        fields = fields
    )
  }

  /**
   * Reads a full primitive array record after a primitive array dump tag.
   */
  fun readPrimitiveArrayDumpRecord(): PrimitiveArrayDumpRecord {
    val id = readId()
    val stackTraceSerialNumber = readInt()
    // length
    val arrayLength = readInt()
    return when (val type = readUnsignedByte()) {
      BOOLEAN_TYPE -> BooleanArrayDump(
          id, stackTraceSerialNumber, readBooleanArray(arrayLength)
      )
      CHAR_TYPE -> CharArrayDump(
          id, stackTraceSerialNumber, readCharArray(arrayLength)
      )
      FLOAT_TYPE -> FloatArrayDump(
          id, stackTraceSerialNumber, readFloatArray(arrayLength)
      )
      DOUBLE_TYPE -> DoubleArrayDump(
          id, stackTraceSerialNumber, readDoubleArray(arrayLength)
      )
      BYTE_TYPE -> ByteArrayDump(
          id, stackTraceSerialNumber, readByteArray(arrayLength)
      )
      SHORT_TYPE -> ShortArrayDump(
          id, stackTraceSerialNumber, readShortArray(arrayLength)
      )
      INT_TYPE -> IntArrayDump(
          id, stackTraceSerialNumber, readIntArray(arrayLength)
      )
      LONG_TYPE -> LongArrayDump(
          id, stackTraceSerialNumber, readLongArray(arrayLength)
      )
      else -> throw IllegalStateException("Unexpected type $type")
    }
  }

  /**
   * Reads a full object array record after a object array dump tag.
   */
  fun readObjectArrayDumpRecord(
  ): ObjectArrayDumpRecord {
    val id = readId()
    // stack trace serial number
    val stackTraceSerialNumber = readInt()
    val arrayLength = readInt()
    val arrayClassId = readId()
    val elementIds = readIdArray(arrayLength)
    return ObjectArrayDumpRecord(
        id = id,
        stackTraceSerialNumber = stackTraceSerialNumber,
        arrayClassId = arrayClassId,
        elementIds = elementIds
    )
  }

  /**
   * Reads a value in the heap dump, which can be a reference or a primitive type.
   */
  fun readValue(type: Int): ValueHolder {
    return when (type) {
      PrimitiveType.REFERENCE_HPROF_TYPE -> ReferenceHolder(readId())
      BOOLEAN_TYPE -> BooleanHolder(readBoolean())
      CHAR_TYPE -> CharHolder(readChar())
      FLOAT_TYPE -> FloatHolder(readFloat())
      DOUBLE_TYPE -> DoubleHolder(readDouble())
      BYTE_TYPE -> ByteHolder(readByte())
      SHORT_TYPE -> ShortHolder(readShort())
      INT_TYPE -> IntHolder(readInt())
      LONG_TYPE -> LongHolder(readLong())
      else -> throw IllegalStateException("Unknown type $type")
    }
  }

  private fun typeSize(type: Int): Int {
    return typeSizes.getValue(type)
  }

  private fun readShort(): Short {
    byteReadCount += SHORT_SIZE
    return source.readShort()
  }

  private fun readInt(): Int {
    byteReadCount += INT_SIZE
    return source.readInt()
  }

  private fun readIdArray(arrayLength: Int): LongArray {
    return LongArray(arrayLength) { readId() }
  }

  private fun readBooleanArray(arrayLength: Int): BooleanArray {
    return BooleanArray(arrayLength) { readByte().toInt() != 0 }
  }

  private fun readCharArray(arrayLength: Int): CharArray {
    return readString(CHAR_SIZE * arrayLength, Charsets.UTF_16BE).toCharArray()
  }

  private fun readString(
    byteCount: Int,
    charset: Charset
  ): String {
    byteReadCount += byteCount
    return source.readString(byteCount.toLong(), charset)
  }

  private fun readFloatArray(arrayLength: Int): FloatArray {
    return FloatArray(arrayLength) { readFloat() }
  }

  private fun readDoubleArray(arrayLength: Int): DoubleArray {
    return DoubleArray(arrayLength) { readDouble() }
  }

  private fun readShortArray(arrayLength: Int): ShortArray {
    return ShortArray(arrayLength) { readShort() }
  }

  private fun readIntArray(arrayLength: Int): IntArray {
    return IntArray(arrayLength) { readInt() }
  }

  private fun readLongArray(arrayLength: Int): LongArray {
    return LongArray(arrayLength) { readLong() }
  }

  private fun readLong(): Long {
    byteReadCount += LONG_SIZE
    return source.readLong()
  }

  private fun exhausted() = source.exhausted()

  private fun skip(byteCount: Long) {
    byteReadCount += byteCount
    return source.skip(byteCount)
  }

  private fun readByte(): Byte {
    byteReadCount += BYTE_SIZE
    return source.readByte()
  }

  private fun readBoolean(): Boolean {
    byteReadCount += BOOLEAN_SIZE
    return source.readByte().toInt() != 0
  }

  private fun readByteArray(byteCount: Int): ByteArray {
    byteReadCount += byteCount
    return source.readByteArray(byteCount.toLong())
  }

  private fun readChar(): Char {
    return readString(CHAR_SIZE, Charsets.UTF_16BE)[0]
  }

  private fun readFloat(): Float {
    return Float.fromBits(readInt())
  }

  private fun readDouble(): Double {
    return Double.fromBits(readLong())
  }

  private fun readId(): Long {
    // As long as we don't interpret IDs, reading signed values here is fine.
    return when (identifierByteSize) {
      1 -> readByte().toLong()
      2 -> readShort().toLong()
      4 -> readInt().toLong()
      8 -> readLong()
      else -> throw IllegalArgumentException("ID Length must be 1, 2, 4, or 8")
    }
  }

  private fun readUtf8(byteCount: Long): String {
    byteReadCount += byteCount
    return source.readUtf8(byteCount)
  }

  private fun readUnsignedInt(): Long {
    return readInt().toLong() and INT_MASK
  }

  private fun readUnsignedByte(): Int {
    return readByte().toInt() and BYTE_MASK
  }

  private fun readUnsignedShort(): Int {
    return readShort().toInt() and 0xFFFF
  }

  private fun skip(byteCount: Int) {
    byteReadCount += byteCount
    return source.skip(byteCount.toLong())
  }

  private fun skipInstanceDumpRecord() {
    skip(identifierByteSize + INT_SIZE + identifierByteSize)
    val remainingBytesInInstance = readInt()
    skip(remainingBytesInInstance)
  }

  private fun skipClassDumpRecord() {
    skip(
        identifierByteSize + INT_SIZE + identifierByteSize + identifierByteSize + identifierByteSize + identifierByteSize + identifierByteSize + identifierByteSize + INT_SIZE
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
      skip(identifierByteSize)
      val type = readUnsignedByte()
      skip(typeSize(type))
    }

    val fieldCount = readUnsignedShort()
    skip(fieldCount * (identifierByteSize + BYTE_SIZE))
  }

  private fun skipObjectArrayDumpRecord() {
    skip(identifierByteSize + INT_SIZE)
    val arrayLength = readInt()
    skip(identifierByteSize + arrayLength * identifierByteSize)
  }

  private fun skipPrimitiveArrayDumpRecord() {
    skip(identifierByteSize + INT_SIZE)
    val arrayLength = readInt()
    val type = readUnsignedByte()
    skip(identifierByteSize + arrayLength * typeSize(type))
  }

  private fun readHeapDumpInfoRecord(): HeapDumpInfoRecord {
    val heapId = readInt()
    return HeapDumpInfoRecord(heapId = heapId, heapNameStringId = readId())
  }

  private fun skipHeapDumpInfoRecord() {
    skip(identifierByteSize + identifierByteSize)
  }

  companion object {
    private val BOOLEAN_SIZE = BOOLEAN.byteSize
    private val CHAR_SIZE = CHAR.byteSize
    private val FLOAT_SIZE = FLOAT.byteSize
    private val DOUBLE_SIZE = DOUBLE.byteSize
    private val BYTE_SIZE = BYTE.byteSize
    private val SHORT_SIZE = SHORT.byteSize
    private val INT_SIZE = PrimitiveType.INT.byteSize
    private val LONG_SIZE = LONG.byteSize

    private val BOOLEAN_TYPE = BOOLEAN.hprofType
    private val CHAR_TYPE = CHAR.hprofType
    private val FLOAT_TYPE = FLOAT.hprofType
    private val DOUBLE_TYPE = DOUBLE.hprofType
    private val BYTE_TYPE = BYTE.hprofType
    private val SHORT_TYPE = SHORT.hprofType
    private val INT_TYPE = PrimitiveType.INT.hprofType
    private val LONG_TYPE = LONG.hprofType

    private const val INT_MASK = 0xffffffffL
    private const val BYTE_MASK = 0xff

    internal const val STRING_IN_UTF8 = 0x01
    internal const val LOAD_CLASS = 0x02
    internal const val UNLOAD_CLASS = 0x03
    internal const val STACK_FRAME = 0x04
    internal const val STACK_TRACE = 0x05
    internal const val ALLOC_SITES = 0x06
    internal const val HEAP_SUMMARY = 0x07
    // TODO Maybe parse this?
    internal const val START_THREAD = 0x0a
    internal const val END_THREAD = 0x0b
    internal const val HEAP_DUMP = 0x0c
    internal const val HEAP_DUMP_SEGMENT = 0x1c
    internal const val HEAP_DUMP_END = 0x2c
    internal const val CPU_SAMPLES = 0x0d
    internal const val CONTROL_SETTINGS = 0x0e
    internal const val ROOT_UNKNOWN = 0xff
    internal const val ROOT_JNI_GLOBAL = 0x01
    internal const val ROOT_JNI_LOCAL = 0x02
    internal const val ROOT_JAVA_FRAME = 0x03
    internal const val ROOT_NATIVE_STACK = 0x04
    internal const val ROOT_STICKY_CLASS = 0x05
    internal const val ROOT_THREAD_BLOCK = 0x06
    internal const val ROOT_MONITOR_USED = 0x07
    internal const val ROOT_THREAD_OBJECT = 0x08
    internal const val CLASS_DUMP = 0x20
    internal const val INSTANCE_DUMP = 0x21
    internal const val OBJECT_ARRAY_DUMP = 0x22
    internal const val PRIMITIVE_ARRAY_DUMP = 0x23

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
    internal const val HEAP_DUMP_INFO = 0xfe
    internal const val ROOT_INTERNED_STRING = 0x89
    internal const val ROOT_FINALIZING = 0x8a
    internal const val ROOT_DEBUGGER = 0x8b
    internal const val ROOT_REFERENCE_CLEANUP = 0x8c
    internal const val ROOT_VM_INTERNAL = 0x8d
    internal const val ROOT_JNI_MONITOR = 0x8e
    internal const val ROOT_UNREACHABLE = 0x90
    internal const val PRIMITIVE_ARRAY_NODATA = 0xc3
  }

}
