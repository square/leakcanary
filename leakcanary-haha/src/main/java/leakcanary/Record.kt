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

        class BooleanArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: BooleanArray
        ) : PrimitiveArrayDumpRecord()

        class CharArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: CharArray
        ) : PrimitiveArrayDumpRecord()

        class FloatArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: FloatArray
        ) : PrimitiveArrayDumpRecord()

        class DoubleArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: DoubleArray
        ) : PrimitiveArrayDumpRecord()

        class ByteArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: ByteArray
        ) : PrimitiveArrayDumpRecord()

        class ShortArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: ShortArray
        ) : PrimitiveArrayDumpRecord()

        class IntArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: IntArray
        ) : PrimitiveArrayDumpRecord()

        class LongArrayDump(
          override val id: Long,
          override val stackTraceSerialNumber: Int,
          val array: LongArray
        ) : PrimitiveArrayDumpRecord()
      }
    }

    class HeapDumpInfoRecord(
      val heapId: Int,
      val heapNameStringId: Long
    ) : HeapDumpRecord()
  }
}