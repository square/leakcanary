package shark

import okio.Source
import shark.HprofRecord.HeapDumpEndRecord
import shark.HprofRecord.HeapDumpRecord
import shark.HprofRecord.HeapDumpRecord.GcRootRecord
import shark.HprofRecord.HeapDumpRecord.HeapDumpInfoRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassSkipContentRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceSkipContentRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArraySkipContentRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArraySkipContentRecord
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StackFrameRecord
import shark.HprofRecord.StackTraceRecord
import shark.HprofRecord.StringRecord
import shark.PrimitiveType.BOOLEAN
import shark.PrimitiveType.Companion.REFERENCE_HPROF_TYPE
import shark.PrimitiveType.INT
import java.io.File
import kotlin.reflect.KClass

/**
 * Reads the entire content of a Hprof source in one fell swoop.
 * Call [readerFor] to obtain a new instance.
 */
class StreamingHprofReader private constructor(
  private val sourceProvider: StreamingSourceProvider,
  private val header: HprofHeader
) {

  /**
   * Obtains a new source to read all hprof records from and calls [listener] back for each record
   * that matches one of the provided [recordTypes].
   *
   * @return the number of bytes read from the source
   */
  @Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth")
  fun readRecords(
    recordTypes: Set<KClass<out HprofRecord>>,
    listener: OnHprofRecordListener
  ): Long {
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
    val readClassSkipContentRecord = ClassSkipContentRecord::class in recordTypes
    val readInstanceDumpRecord = readAllObjectRecords || InstanceDumpRecord::class in recordTypes
    val readInstanceSkipContentRecord = InstanceSkipContentRecord::class in recordTypes
    val readObjectArrayDumpRecord =
      readAllObjectRecords || ObjectArrayDumpRecord::class in recordTypes
    val readObjectArraySkipContentRecord = ObjectArraySkipContentRecord::class in recordTypes
    val readPrimitiveArrayDumpRecord =
      readAllObjectRecords || PrimitiveArrayDumpRecord::class in recordTypes
    val readPrimitiveArraySkipContentRecord = PrimitiveArraySkipContentRecord::class in recordTypes

    val reusedInstanceSkipContentRecord = InstanceSkipContentRecord(0, 0, 0)
    val reusedClassSkipContentRecord = ClassSkipContentRecord(0, 0, 0, 0, 0, 0, 0, 0, 0)
    val reusedObjectArraySkipContentRecord = ObjectArraySkipContentRecord(0, 0, 0, 0)
    val reusedPrimitiveArraySkipContentRecord =
      PrimitiveArraySkipContentRecord(0, 0, 0, BOOLEAN)
    val reusedLoadClassRecord = LoadClassRecord(0, 0, 0, 0)

    return sourceProvider.openStreamingSource().use { source ->
      val reader = HprofRecordReader(header, source)
      reader.skip(header.recordsPosition)

      // Local ref optimizations
      val intByteSize = INT.byteSize
      val identifierByteSize = reader.sizeOf(REFERENCE_HPROF_TYPE)


      while (!source.exhausted()) {
        // type of the record
        val tag = reader.readUnsignedByte()

        // number of microseconds since the time stamp in the header
        reader.skip(intByteSize)

        // number of bytes that follow and belong to this record
        val length = reader.readUnsignedInt()

        when (tag) {
          STRING_IN_UTF8 -> {
            if (readStringRecord) {
              val recordPosition = reader.bytesRead
              val record = reader.readStringRecord(length)
              listener.onHprofRecord(recordPosition, record)
            } else {
              reader.skip(length)
            }
          }
          LOAD_CLASS -> {
            if (readLoadClassRecord) {
              val recordPosition = reader.bytesRead
              with(reader) {
                reusedLoadClassRecord.read()
              }
              listener.onHprofRecord(recordPosition, reusedLoadClassRecord)
            } else {
              reader.skip(length)
            }
          }
          STACK_FRAME -> {
            if (readStackFrameRecord) {
              val recordPosition = reader.bytesRead
              val record = reader.readStackFrameRecord()
              listener.onHprofRecord(recordPosition, record)
            } else {
              reader.skip(length)
            }
          }
          STACK_TRACE -> {
            if (readStackTraceRecord) {
              val recordPosition = reader.bytesRead
              val record = reader.readStackTraceRecord()
              listener.onHprofRecord(recordPosition, record)
            } else {
              reader.skip(length)
            }
          }
          HEAP_DUMP, HEAP_DUMP_SEGMENT -> {
            val heapDumpStart = reader.bytesRead
            var previousTag = 0
            var previousTagPosition = 0L
            while (reader.bytesRead - heapDumpStart < length) {
              val heapDumpTagPosition = reader.bytesRead
              val heapDumpTag = reader.readUnsignedByte()
              when (heapDumpTag) {
                ROOT_UNKNOWN -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    val record = reader.readUnknownGcRootRecord()
                    listener.onHprofRecord(recordPosition, record)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }
                ROOT_JNI_GLOBAL -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    val gcRootRecord = reader.readJniGlobalGcRootRecord()
                    listener.onHprofRecord(recordPosition, gcRootRecord)
                  } else {
                    reader.skip(identifierByteSize + identifierByteSize)
                  }
                }
                ROOT_JNI_LOCAL -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    val gcRootRecord = reader.readJniLocalGcRootRecord()
                    listener.onHprofRecord(recordPosition, gcRootRecord)
                  } else {
                    reader.skip(identifierByteSize + intByteSize + intByteSize)
                  }
                }

                ROOT_JAVA_FRAME -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    val gcRootRecord = reader.readJavaFrameGcRootRecord()
                    listener.onHprofRecord(recordPosition, gcRootRecord)
                  } else {
                    reader.skip(identifierByteSize + intByteSize + intByteSize)
                  }
                }

                ROOT_NATIVE_STACK -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    val gcRootRecord = reader.readNativeStackGcRootRecord()
                    listener.onHprofRecord(recordPosition, gcRootRecord)
                  } else {
                    reader.skip(identifierByteSize + intByteSize)
                  }
                }

                ROOT_STICKY_CLASS -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    val gcRootRecord = reader.readStickyClassGcRootRecord()
                    listener.onHprofRecord(recordPosition, gcRootRecord)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }

                // An object that was referenced from an active thread block.
                ROOT_THREAD_BLOCK -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    listener.onHprofRecord(recordPosition, reader.readThreadBlockGcRootRecord())
                  } else {
                    reader.skip(identifierByteSize + intByteSize)
                  }
                }

                ROOT_MONITOR_USED -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    val gcRootRecord = reader.readMonitorUsedGcRootRecord()
                    listener.onHprofRecord(recordPosition, gcRootRecord)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }

                ROOT_THREAD_OBJECT -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    val gcRootRecord = reader.readThreadObjectGcRootRecord()
                    listener.onHprofRecord(recordPosition, gcRootRecord)
                  } else {
                    reader.skip(identifierByteSize + intByteSize + intByteSize)
                  }
                }

                ROOT_INTERNED_STRING -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    val gcRootRecord = reader.readInternedStringGcRootRecord()
                    listener.onHprofRecord(recordPosition, gcRootRecord)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }

                ROOT_FINALIZING -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    val gcRootRecord = reader.readFinalizingGcRootRecord()
                    listener.onHprofRecord(recordPosition, gcRootRecord)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }

                ROOT_DEBUGGER -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    val gcRootRecord = reader.readDebuggerGcRootRecord()
                    listener.onHprofRecord(recordPosition, gcRootRecord)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }

                ROOT_REFERENCE_CLEANUP -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    val gcRootRecord = reader.readReferenceCleanupGcRootRecord()
                    listener.onHprofRecord(recordPosition, gcRootRecord)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }

                ROOT_VM_INTERNAL -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    val gcRootRecord = reader.readVmInternalGcRootRecord()
                    listener.onHprofRecord(recordPosition, gcRootRecord)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }

                ROOT_JNI_MONITOR -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    val gcRootRecord = reader.readJniMonitorGcRootRecord()
                    listener.onHprofRecord(recordPosition, gcRootRecord)
                  } else {
                    reader.skip(identifierByteSize + intByteSize + intByteSize)
                  }
                }

                ROOT_UNREACHABLE -> {
                  if (readGcRootRecord) {
                    val recordPosition = reader.bytesRead
                    val gcRootRecord = reader.readUnreachableGcRootRecord()
                    listener.onHprofRecord(recordPosition, gcRootRecord)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }
                CLASS_DUMP -> {
                  when {
                    readClassDumpRecord -> {
                      val recordPosition = reader.bytesRead
                      val record = reader.readClassDumpRecord()
                      listener.onHprofRecord(recordPosition, record)
                    }
                    readClassSkipContentRecord -> {
                      val recordPosition = reader.bytesRead
                      with(reader) {
                        reusedClassSkipContentRecord.read()
                      }
                      listener.onHprofRecord(recordPosition, reusedClassSkipContentRecord)
                    }
                    else -> reader.skipClassDumpRecord()
                  }
                }
                INSTANCE_DUMP -> {
                  when {
                    readInstanceDumpRecord -> {
                      val recordPosition = reader.bytesRead
                      val record = reader.readInstanceDumpRecord()
                      listener.onHprofRecord(recordPosition, record)
                    }
                    readInstanceSkipContentRecord -> {
                      val recordPosition = reader.bytesRead
                      with(reader) {
                        reusedInstanceSkipContentRecord.read()
                      }
                      listener.onHprofRecord(recordPosition, reusedInstanceSkipContentRecord)
                    }
                    else -> reader.skipInstanceDumpRecord()
                  }
                }

                OBJECT_ARRAY_DUMP -> {
                  when {
                    readObjectArrayDumpRecord -> {
                      val recordPosition = reader.bytesRead
                      val arrayRecord = reader.readObjectArrayDumpRecord()
                      listener.onHprofRecord(recordPosition, arrayRecord)
                    }
                    readObjectArraySkipContentRecord -> {
                      val recordPosition = reader.bytesRead
                      with(reader) {
                        reusedObjectArraySkipContentRecord.read()
                      }
                      listener.onHprofRecord(recordPosition, reusedObjectArraySkipContentRecord)
                    }
                    else -> reader.skipObjectArrayDumpRecord()
                  }
                }

                PRIMITIVE_ARRAY_DUMP -> {
                  when {
                    readPrimitiveArrayDumpRecord -> {
                      val recordPosition = reader.bytesRead
                      val record = reader.readPrimitiveArrayDumpRecord()
                      listener.onHprofRecord(recordPosition, record)
                    }
                    readPrimitiveArraySkipContentRecord -> {
                      val recordPosition = reader.bytesRead
                      with(reader) {
                        reusedPrimitiveArraySkipContentRecord.read()
                      }
                      listener.onHprofRecord(
                          recordPosition, reusedPrimitiveArraySkipContentRecord
                      )
                    }
                    else -> reader.skipPrimitiveArrayDumpRecord()
                  }
                }

                PRIMITIVE_ARRAY_NODATA -> {
                  throw UnsupportedOperationException("PRIMITIVE_ARRAY_NODATA cannot be parsed")
                }

                HEAP_DUMP_INFO -> {
                  if (readHeapDumpInfoRecord) {
                    val recordPosition = reader.bytesRead
                    val record = reader.readHeapDumpInfoRecord()
                    listener.onHprofRecord(recordPosition, record)
                  } else {
                    reader.skipHeapDumpInfoRecord()
                  }
                }
                else -> throw IllegalStateException(
                    "Unknown tag ${"0x%02x".format(
                        heapDumpTag
                    )} at $heapDumpTagPosition after ${"0x%02x".format(
                        previousTag
                    )} at $previousTagPosition"
                )
              }
              previousTag = heapDumpTag
              previousTagPosition = heapDumpTagPosition
            }
          }
          HEAP_DUMP_END -> {
            if (readHeapDumpEndRecord) {
              val recordPosition = reader.bytesRead
              val record = HeapDumpEndRecord
              listener.onHprofRecord(recordPosition, record)
            }
          }
          else -> {
            reader.skip(length)
          }
        }
      }
      reader.bytesRead
    }
  }

  companion object {
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

    /**
     * Creates a [StreamingHprofReader] for the provided [hprofFile]. [hprofHeader] will be read from
     * [hprofFile] unless you provide it.
     */
    fun readerFor(
      hprofFile: File,
      hprofHeader: HprofHeader = HprofHeader.parseHeaderOf(hprofFile)
    ): StreamingHprofReader {
      val sourceProvider = FileSourceProvider(hprofFile)
      return readerFor(sourceProvider, hprofHeader)
    }

    /**
     * Creates a [StreamingHprofReader] that will call [StreamingSourceProvider.openStreamingSource]
     * on every [readRecords] to obtain a [Source] to read the hprof data from. Before reading the
     * hprof records, [StreamingHprofReader] will skip [HprofHeader.recordsPosition] bytes.
     */
    fun readerFor(
      hprofSourceProvider: StreamingSourceProvider,
      hprofHeader: HprofHeader = hprofSourceProvider.openStreamingSource()
          .use { HprofHeader.parseHeaderOf(it) }
    ): StreamingHprofReader {
      return StreamingHprofReader(hprofSourceProvider, hprofHeader)
    }
  }
}
