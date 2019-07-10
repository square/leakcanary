package leakcanary

sealed class Record {
  class StringRecord(
    val id: Long,
    val string: String
  ) : Record()

  class LoadClassRecord(
    val classSerialNumber: Int,
    val id: Long,
    val stackTraceSerialNumber: Int,
    val classNameStringId: Long
  ) : Record()

  object HeapDumpEndRecord : Record()

  class StackFrameRecord(
    val id: Long,
    val methodNameStringId: Long,
    val methodSignatureStringId: Long,
    val sourceFileNameStringId: Long,
    val classSerialNumber: Int,
    /**
     * >0 line number
     * 0 no line information available
     * -1 unknown location
     * -2 compiled method (Not implemented)
     * -3 native method (Not implemented)
     */
    val lineNumber: Int
  ) : Record()

  class StackTraceRecord(
    val stackTraceSerialNumber: Int,
    val threadSerialNumber: Int,
    val stackFrameIds: LongArray
  ) : Record()

  sealed class HeapDumpRecord : Record() {
    class GcRootRecord(
      val gcRoot: GcRoot
    ) : HeapDumpRecord()

    sealed class ObjectRecord : HeapDumpRecord() {
      class ClassDumpRecord(
        val id: Long,
        val stackTraceSerialNumber: Int,
        val superClassId: Long,
        val classLoaderId: Long,
        val signersId: Long,
        val protectionDomainId: Long,
        val instanceSize: Int,
        val staticFields: List<StaticFieldRecord>,
        val fields: List<FieldRecord>
      ) : ObjectRecord() {
        data class StaticFieldRecord(
          val nameStringId: Long,
          val type: Int,
          val value: HeapValue
        )

        data class FieldRecord(
          val nameStringId: Long,
          val type: Int
        )
      }

      class InstanceDumpRecord(
        val id: Long,
        val stackTraceSerialNumber: Int,
        val classId: Long,
        val fieldValues: ByteArray
      ) : ObjectRecord()

      class ObjectArrayDumpRecord(
        val id: Long,
        val stackTraceSerialNumber: Int,
        val arrayClassId: Long,
        val elementIds: LongArray
      ) : ObjectRecord()

      /**
       * Note: we could move the arrays to the parent class as a ByteString or ByteArray
       * and then each subtype can create a new array of the right type if needed.
       * However, experimenting with live parsing has shown that we never to read arrays except
       * when we want to display leak trace information, in which case we do need the data.
       */
      sealed class PrimitiveArrayDumpRecord : ObjectRecord() {
        abstract val id: Long
        abstract val stackTraceSerialNumber: Int
        abstract val size: Int

        class BooleanArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: BooleanArray
        ) : PrimitiveArrayDumpRecord() {
          override val size: Int
            get() = array.size
        }

        class CharArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: CharArray
        ) : PrimitiveArrayDumpRecord() {
          override val size: Int
            get() = array.size
        }

        class FloatArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: FloatArray
        ) : PrimitiveArrayDumpRecord() {
          override val size: Int
            get() = array.size
        }

        class DoubleArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: DoubleArray
        ) : PrimitiveArrayDumpRecord() {
          override val size: Int
            get() = array.size
        }

        class ByteArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: ByteArray
        ) : PrimitiveArrayDumpRecord() {
          override val size: Int
            get() = array.size
        }

        class ShortArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: ShortArray
        ) : PrimitiveArrayDumpRecord() {
          override val size: Int
            get() = array.size
        }

        class IntArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: IntArray
        ) : PrimitiveArrayDumpRecord() {
          override val size: Int
            get() = array.size
        }

        class LongArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: LongArray
        ) : PrimitiveArrayDumpRecord() {
          override val size: Int
            get() = array.size
        }
      }
    }

    class HeapDumpInfoRecord(
      val heapId: Int,
      val heapNameStringId: Long
    ) : HeapDumpRecord()
  }
}