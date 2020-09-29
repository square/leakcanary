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
import shark.HprofRecord.HeapDumpRecord.HeapDumpInfoRecord
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

/**
 * Reads hprof content from an Okio [BufferedSource].
 *
 * Binary Dump Format reference: http://hg.openjdk.java.net/jdk6/jdk6/jdk/raw-file/tip/src/share
 * /demo/jvmti/hprof/manual.html#mozTocId848088
 *
 * The Android Hprof format differs in some ways from that reference. This parser implementation
 * is largely adapted from https://android.googlesource.com/platform/tools/base/+/studio-master-dev
 * /perflib/src/main/java/com/android/tools/perflib
 *
 * Not thread safe, should be used from a single thread.
 */
@Suppress("LargeClass", "TooManyFunctions")
class HprofRecordReader internal constructor(
  header: HprofHeader,
  private val source: BufferedSource
) {

  /**
   * How many bytes this reader has read from [source]. Can only increase.
   */
  var bytesRead = 0L
    private set

  private val identifierByteSize = header.identifierByteSize

  private val typeSizes: IntArray

  init {
    val typeSizesMap =
      PrimitiveType.byteSizeByHprofType + (PrimitiveType.REFERENCE_HPROF_TYPE to identifierByteSize)

    val maxKey = typeSizesMap.keys.max()!!

    typeSizes = IntArray(maxKey + 1) { key ->
      typeSizesMap[key] ?: 0
    }
  }

  fun sizeOf(type: Int) = typeSizes[type]

  fun readStringRecord(length: Long) = StringRecord(
      id = readId(),
      string = readUtf8(length - identifierByteSize)
  )

  fun readLoadClassRecord() = LoadClassRecord(
    classSerialNumber = readInt(),
    id = readId(),
    stackTraceSerialNumber = readInt(),
    classNameStringId = readId()
  )

  fun readStackFrameRecord() = StackFrameRecord(
      id = readId(),
      methodNameStringId = readId(),
      methodSignatureStringId = readId(),
      sourceFileNameStringId = readId(),
      classSerialNumber = readInt(),
      lineNumber = readInt()
  )

  fun readStackTraceRecord() = StackTraceRecord(
      stackTraceSerialNumber = readInt(),
      threadSerialNumber = readInt(),
      stackFrameIds = readIdArray(readInt())
  )

  fun readUnknownGcRootRecord() = Unknown(id = readId())

  fun readJniGlobalGcRootRecord() = JniGlobal(
      id = readId(),
      jniGlobalRefId = readId()
  )

  fun readJniLocalGcRootRecord() = JniLocal(
      id = readId(),
      threadSerialNumber = readInt(),
      frameNumber = readInt()
  )

  fun readJavaFrameGcRootRecord() = JavaFrame(
      id = readId(),
      threadSerialNumber = readInt(),
      frameNumber = readInt()
  )

  fun readNativeStackGcRootRecord() = NativeStack(
      id = readId(),
      threadSerialNumber = readInt()
  )

  fun readStickyClassGcRootRecord() = StickyClass(id = readId())

  fun readThreadBlockGcRootRecord() = ThreadBlock(id = readId(), threadSerialNumber = readInt())

  fun readMonitorUsedGcRootRecord() =  MonitorUsed(id = readId())

  fun readThreadObjectGcRootRecord() = ThreadObject(
      id = readId(),
      threadSerialNumber = readInt(),
      stackTraceSerialNumber = readInt()
  )

  fun readInternedStringGcRootRecord() = InternedString(id = readId())

  fun readFinalizingGcRootRecord() = Finalizing(id = readId())

  fun readDebuggerGcRootRecord() = Debugger(id = readId())

  fun readReferenceCleanupGcRootRecord() = ReferenceCleanup(id = readId())

  fun readVmInternalGcRootRecord() = VmInternal(id = readId())

  fun readJniMonitorGcRootRecord() = JniMonitor(
      id = readId(),
      stackTraceSerialNumber = readInt(),
      stackDepth = readInt()
  )

  fun readUnreachableGcRootRecord() = Unreachable(id = readId())

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

  fun readHeapDumpInfoRecord(): HeapDumpInfoRecord {
    val heapId = readInt()
    return HeapDumpInfoRecord(heapId = heapId, heapNameStringId = readId())
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
      skip(typeSizes[readUnsignedByte()])
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
  fun readObjectArrayDumpRecord(): ObjectArrayDumpRecord {
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

  fun skipClassDumpHeader() {
    skip(INT_SIZE * 2 + identifierByteSize * 7)
    skipClassDumpConstantPool()
  }

  fun skipClassDumpConstantPool() {
    // Skip over the constant pool
    val constantPoolCount = readUnsignedShort()
    for (i in 0 until constantPoolCount) {
      // constant pool index
      skip(SHORT.byteSize)
      skip(sizeOf(readUnsignedByte()))
    }
  }

  fun skipClassDumpStaticFields() {
    val staticFieldCount = readUnsignedShort()
    for (i in 0 until staticFieldCount) {
      skip(identifierByteSize)
      val type = readUnsignedByte()
      skip(
          if (type == PrimitiveType.REFERENCE_HPROF_TYPE) {
            identifierByteSize
          } else {
            PrimitiveType.byteSizeByHprofType.getValue(type)
          }
      )
    }
  }

  fun skipClassDumpFields() {
    val fieldCount = readUnsignedShort()
    skip((identifierByteSize + 1) * fieldCount)
  }

  fun skipInstanceDumpRecord() {
    skip(identifierByteSize + INT_SIZE + identifierByteSize)
    val remainingBytesInInstance = readInt()
    skip(remainingBytesInInstance)
  }

  fun skipClassDumpRecord() {
    skip(
        identifierByteSize + INT_SIZE + identifierByteSize + identifierByteSize + identifierByteSize +
            identifierByteSize + identifierByteSize + identifierByteSize + INT_SIZE
    )
    // Skip over the constant pool
    val constantPoolCount = readUnsignedShort()
    for (i in 0 until constantPoolCount) {
      // constant pool index
      skip(SHORT_SIZE)
      skip(typeSizes[readUnsignedByte()])
    }

    val staticFieldCount = readUnsignedShort()

    for (i in 0 until staticFieldCount) {
      skip(identifierByteSize)
      val type = readUnsignedByte()
      skip(typeSizes[type])
    }

    val fieldCount = readUnsignedShort()
    skip(fieldCount * (identifierByteSize + BYTE_SIZE))
  }

  fun skipObjectArrayDumpRecord() {
    skip(identifierByteSize + INT_SIZE)
    val arrayLength = readInt()
    skip(identifierByteSize + arrayLength * identifierByteSize)
  }

  fun skipPrimitiveArrayDumpRecord() {
    skip(identifierByteSize + INT_SIZE)
    val arrayLength = readInt()
    val type = readUnsignedByte()
    skip(arrayLength * typeSizes[type])
  }

  fun skipHeapDumpInfoRecord() {
    skip(identifierByteSize + identifierByteSize)
  }

  fun skip(byteCount: Int) {
    bytesRead += byteCount
    return source.skip(byteCount.toLong())
  }

  fun skip(byteCount: Long) {
    bytesRead += byteCount
    return source.skip(byteCount)
  }

  fun readUnsignedInt(): Long {
    return readInt().toLong() and INT_MASK
  }

  fun readUnsignedByte(): Int {
    return readByte().toInt() and BYTE_MASK
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

  fun readShort(): Short {
    bytesRead += SHORT_SIZE
    return source.readShort()
  }

  fun readInt(): Int {
    bytesRead += INT_SIZE
    return source.readInt()
  }

  fun readIdArray(arrayLength: Int): LongArray {
    return LongArray(arrayLength) { readId() }
  }

  fun readBooleanArray(arrayLength: Int): BooleanArray {
    return BooleanArray(arrayLength) { readByte().toInt() != 0 }
  }

  fun readCharArray(arrayLength: Int): CharArray {
    return CharArray(arrayLength) {
      readChar()
    }
  }

  fun readString(
    byteCount: Int,
    charset: Charset
  ): String {
    bytesRead += byteCount
    return source.readString(byteCount.toLong(), charset)
  }

  fun readFloatArray(arrayLength: Int): FloatArray {
    return FloatArray(arrayLength) { readFloat() }
  }

  fun readDoubleArray(arrayLength: Int): DoubleArray {
    return DoubleArray(arrayLength) { readDouble() }
  }

  fun readShortArray(arrayLength: Int): ShortArray {
    return ShortArray(arrayLength) { readShort() }
  }

  fun readIntArray(arrayLength: Int): IntArray {
    return IntArray(arrayLength) { readInt() }
  }

  fun readLongArray(arrayLength: Int): LongArray {
    return LongArray(arrayLength) { readLong() }
  }

  fun readLong(): Long {
    bytesRead += LONG_SIZE
    return source.readLong()
  }

  fun readByte(): Byte {
    bytesRead += BYTE_SIZE
    return source.readByte()
  }

  fun readBoolean(): Boolean {
    bytesRead += BOOLEAN_SIZE
    return source.readByte()
        .toInt() != 0
  }

  fun readByteArray(byteCount: Int): ByteArray {
    bytesRead += byteCount
    return source.readByteArray(byteCount.toLong())
  }

  fun readChar(): Char {
    return readString(CHAR_SIZE, Charsets.UTF_16BE)[0]
  }

  fun readFloat(): Float {
    return Float.fromBits(readInt())
  }

  fun readDouble(): Double {
    return Double.fromBits(readLong())
  }

  fun readId(): Long {
    // As long as we don't interpret IDs, reading signed values here is fine.
    return when (identifierByteSize) {
      1 -> readByte().toLong()
      2 -> readShort().toLong()
      4 -> readInt().toLong()
      8 -> readLong()
      else -> throw IllegalArgumentException("ID Length must be 1, 2, 4, or 8")
    }
  }

  fun readUtf8(byteCount: Long): String {
    bytesRead += byteCount
    return source.readUtf8(byteCount)
  }

  fun readUnsignedShort(): Int {
    return readShort().toInt() and 0xFFFF
  }

  companion object {
    private val BOOLEAN_SIZE = BOOLEAN.byteSize
    private val CHAR_SIZE = CHAR.byteSize
    private val BYTE_SIZE = BYTE.byteSize
    private val SHORT_SIZE = SHORT.byteSize
    private val INT_SIZE = INT.byteSize
    private val LONG_SIZE = LONG.byteSize

    private val BOOLEAN_TYPE = BOOLEAN.hprofType
    private val CHAR_TYPE = CHAR.hprofType
    private val FLOAT_TYPE = FLOAT.hprofType
    private val DOUBLE_TYPE = DOUBLE.hprofType
    private val BYTE_TYPE = BYTE.hprofType
    private val SHORT_TYPE = SHORT.hprofType
    private val INT_TYPE = INT.hprofType
    private val LONG_TYPE = LONG.hprofType

    private const val INT_MASK = 0xffffffffL
    private const val BYTE_MASK = 0xff
  }
}
