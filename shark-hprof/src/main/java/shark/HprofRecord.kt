package shark

/**
 * A Hprof record. These data structure map 1:1 with how records are written in hprof files.
 */
sealed class HprofRecord {
  class StringRecord(
    val id: Long,
    val string: String
  ) : HprofRecord()

  /**
   * To limit object allocation while parsing, [HprofReader] uses a single instance which is
   * reused after each call to [OnHprofRecordListener.onHprofRecord].
   */
  class LoadClassRecord(
    classSerialNumber: Int,
    id: Long,
    stackTraceSerialNumber: Int,
    classNameStringId: Long
  ) : HprofRecord() {
    var classSerialNumber: Int
      internal set
    var id: Long
      internal set
    var stackTraceSerialNumber: Int
      internal set
    var classNameStringId: Long
      internal set
    init {
      this.classSerialNumber = classSerialNumber
      this.id = id
      this.stackTraceSerialNumber = stackTraceSerialNumber
      this.classNameStringId = classNameStringId
    }
  }

  /**
   * Terminates a series of heap dump segments. Concatenation of heap dump segments equals a
   * heap dump.
   */
  object HeapDumpEndRecord : HprofRecord()

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
  ) : HprofRecord()

  class StackTraceRecord(
    val stackTraceSerialNumber: Int,
    val threadSerialNumber: Int,
    val stackFrameIds: LongArray
  ) : HprofRecord()

  sealed class HeapDumpRecord : HprofRecord() {
    class GcRootRecord(
      val gcRoot: GcRoot
    ) : HeapDumpRecord()

    sealed class ObjectRecord : HeapDumpRecord() {
      class ClassDumpRecord(
        val id: Long,
        val stackTraceSerialNumber: Int,
        val superclassId: Long,
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
          val value: ValueHolder
        )

        data class FieldRecord(
          val nameStringId: Long,
          val type: Int
        )
      }

      /**
       * This isn't a real record type as found in the heap dump. It's an alternative to
       * [ClassDumpRecord] for when you don't need the class content.
       *
       * To limit object allocation while parsing, [HprofReader] uses a single instance which is
       * reused after each call to [OnHprofRecordListener.onHprofRecord].
       */
      class ClassSkipContentRecord(
        id: Long,
        stackTraceSerialNumber: Int,
        superclassId: Long,
        classLoaderId: Long,
        signersId: Long,
        protectionDomainId: Long,
        instanceSize: Int,
        staticFieldCount: Int,
        fieldCount: Int
      ) : ObjectRecord() {
        var id: Long
          internal set
        var stackTraceSerialNumber: Int
          internal set
        var superclassId: Long
          internal set
        var classLoaderId: Long
          internal set
        var signersId: Long
          internal set
        var protectionDomainId: Long
          internal set
        var instanceSize: Int
          internal set
        var staticFieldCount: Int
          internal set
        var fieldCount: Int
          internal set
        var recordSize: Long = 0
          internal set
        var hasRefFields: Boolean = false
          internal set

        init {
          this.id = id
          this.stackTraceSerialNumber = stackTraceSerialNumber
          this.superclassId = superclassId
          this.classLoaderId = classLoaderId
          this.signersId = signersId
          this.protectionDomainId = protectionDomainId
          this.instanceSize = instanceSize
          this.staticFieldCount = staticFieldCount
          this.fieldCount = fieldCount
        }
      }

      class InstanceDumpRecord(
        val id: Long,
        val stackTraceSerialNumber: Int,
        val classId: Long,
        /**
         * Instance field values (this class, followed by super class, etc)
         */
        val fieldValues: ByteArray
      ) : ObjectRecord()

      /**
       * This isn't a real record type as found in the heap dump. It's an alternative to
       * [InstanceDumpRecord] for when you don't need the instance content.
       *
       * To limit object allocation while parsing, [HprofReader] uses a single instance which is
       * reused after each call to [OnHprofRecordListener.onHprofRecord].
       */
      class InstanceSkipContentRecord(
        id: Long,
        stackTraceSerialNumber: Int,
        classId: Long
      ) : ObjectRecord() {
        var id: Long
          internal set
        var stackTraceSerialNumber: Int
          internal set
        var classId: Long
          internal set
        var recordSize: Long = 0
          internal set

        init {
          this.id = id
          this.stackTraceSerialNumber = stackTraceSerialNumber
          this.classId = classId
        }
      }

      class ObjectArrayDumpRecord(
        val id: Long,
        val stackTraceSerialNumber: Int,
        val arrayClassId: Long,
        val elementIds: LongArray
      ) : ObjectRecord()

      /**
       * This isn't a real record type as found in the heap dump. It's an alternative to
       * [ObjectArrayDumpRecord] for when you don't need the array content.
       *
       * To limit object allocation while parsing, [HprofReader] uses a single instance which is
       * reused after each call to [OnHprofRecordListener.onHprofRecord].
       */
      class ObjectArraySkipContentRecord(
        id: Long,
        stackTraceSerialNumber: Int,
        arrayClassId: Long,
        size: Int
      ) : ObjectRecord() {
        var id: Long
          internal set
        var stackTraceSerialNumber: Int
          internal set
        var arrayClassId: Long
          internal set
        var size: Int
          internal set
        var recordSize: Long = 0
          internal set

        init {
          this.id = id
          this.stackTraceSerialNumber = stackTraceSerialNumber
          this.arrayClassId = arrayClassId
          this.size = size
        }
      }

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

      /**
       * This isn't a real record type as found in the heap dump. It's an alternative to
       * [PrimitiveArrayDumpRecord] for when you don't need the array content.
       *
       * To limit object allocation while parsing, [HprofReader] uses a single instance which is
       * reused after each call to [OnHprofRecordListener.onHprofRecord].
       */
      class PrimitiveArraySkipContentRecord(
        id: Long,
        stackTraceSerialNumber: Int,
        size: Int,
        type: PrimitiveType
      ) : ObjectRecord() {
        var id: Long
          internal set
        var stackTraceSerialNumber: Int
          internal set
        var size: Int
          internal set
        var type: PrimitiveType
          internal set
        var recordSize: Long = 0
          internal set

        init {
          this.id = id
          this.stackTraceSerialNumber = stackTraceSerialNumber
          this.size = size
          this.type = type
        }
      }
    }

    class HeapDumpInfoRecord(
      val heapId: Int,
      val heapNameStringId: Long
    ) : HeapDumpRecord()
  }
}