package leakcanary

import leakcanary.GcRoot.Debugger
import leakcanary.GcRoot.Finalizing
import leakcanary.GcRoot.InternedString
import leakcanary.GcRoot.JavaFrame
import leakcanary.GcRoot.JniGlobal
import leakcanary.GcRoot.JniLocal
import leakcanary.GcRoot.JniMonitor
import leakcanary.GcRoot.MonitorUsed
import leakcanary.GcRoot.NativeStack
import leakcanary.GcRoot.ReferenceCleanup
import leakcanary.GcRoot.StickyClass
import leakcanary.GcRoot.ThreadBlock
import leakcanary.GcRoot.ThreadObject
import leakcanary.GcRoot.Unknown
import leakcanary.GcRoot.Unreachable
import leakcanary.GcRoot.VmInternal
import leakcanary.HprofReader.Companion.INT_SIZE
import leakcanary.HprofReader.Companion.LONG_SIZE
import leakcanary.Record.HeapDumpEndRecord
import leakcanary.Record.HeapDumpRecord
import leakcanary.Record.HeapDumpRecord.GcRootRecord
import leakcanary.Record.HeapDumpRecord.HeapDumpInfoRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import leakcanary.Record.LoadClassRecord
import leakcanary.Record.StackFrameRecord
import leakcanary.Record.StackTraceRecord
import leakcanary.Record.StringRecord
import okio.buffer
import okio.source
import java.io.File
import kotlin.reflect.KClass

/**
 * A streaming push heap dump parser.
 *
 * Expected usage: call [readHprofRecords] once, which will go read through the entire heap dump
 * and notify the provided listener of records found.
 *
 * This class is not thread safe, should be used from a single thread.
 *
 * Binary Dump Format reference: http://hg.openjdk.java.net/jdk6/jdk6/jdk/raw-file/tip/src/share/demo/jvmti/hprof/manual.html#mozTocId848088
 *
 * The Android Hprof format differs in some ways from that reference. This parser implementation
 * is largely adapted from https://android.googlesource.com/platform/tools/base/+/studio-master-dev/perflib/src/main/java/com/android/tools/perflib
 */
class HprofPushRecordsParser {

  interface OnRecordListener {
    fun recordTypes(): Set<KClass<out Record>>

    fun onTypeSizesAvailable(typeSizes: Map<Int, Int>)

    fun onRecord(
      position: Long,
      record: Record
    )
  }

  fun readHprofRecords(
    heapDump: File,
    listeners: Set<OnRecordListener>
  ): SeekableHprofReader {
    if (heapDump.length() == 0L) {
      throw IllegalArgumentException("Heap dump file is 0 byte length")
    }
    val inputStream = heapDump.inputStream()
    val channel = inputStream.channel
    val source = inputStream.source()
        .buffer()

    val endOfVersionString = source.indexOf(0)
    source.skip(endOfVersionString + 1)
    val idSize = source.readInt()
    val startPosition = endOfVersionString + 1 + 4

    val reader = SeekableHprofReader(channel, source, startPosition, idSize)

    listeners.forEach { it.onTypeSizesAvailable(reader.typeSizes) }

    reader.readHprofRecords(listeners)
    return reader
  }

  private fun SeekableHprofReader.readHprofRecords(listeners: Set<OnRecordListener>) {

    val readStringRecord = mutableSetOf<OnRecordListener>()
    val readLoadClassRecord = mutableSetOf<OnRecordListener>()
    val readStackFrameRecord = mutableSetOf<OnRecordListener>()
    val readStackTraceRecord = mutableSetOf<OnRecordListener>()
    val readGcRootRecord = mutableSetOf<OnRecordListener>()
    val readClassDumpRecord = mutableSetOf<OnRecordListener>()
    val readInstanceDumpRecord = mutableSetOf<OnRecordListener>()
    val readObjectArrayDumpRecord = mutableSetOf<OnRecordListener>()
    val readPrimitiveArrayDumpRecord = mutableSetOf<OnRecordListener>()
    val readHeapDumpInfoRecord = mutableSetOf<OnRecordListener>()
    val readHeapDumpEnd = mutableSetOf<OnRecordListener>()

    for (listener in listeners) {
      val config = listener.recordTypes()
      config.forEach { recordClass ->
        when (recordClass) {
          Record::class -> {
            readStringRecord += listener
            readLoadClassRecord += listener
            readStackFrameRecord += listener
            readStackTraceRecord += listener
            readGcRootRecord += listener
            readClassDumpRecord += listener
            readInstanceDumpRecord += listener
            readObjectArrayDumpRecord += listener
            readPrimitiveArrayDumpRecord += listener
            readHeapDumpInfoRecord += listener
            readHeapDumpEnd += listener
          }
          StringRecord::class -> {
            readStringRecord += listener
          }
          LoadClassRecord::class -> {
            readLoadClassRecord += listener
          }
          HeapDumpEndRecord::class -> {
            readHeapDumpEnd += listener
          }
          StackFrameRecord::class -> {
            readStackFrameRecord += listener
          }
          StackTraceRecord::class -> {
            readStackTraceRecord += listener
          }
          HeapDumpRecord::class -> {
            readGcRootRecord += listener
            readClassDumpRecord += listener
            readInstanceDumpRecord += listener
            readObjectArrayDumpRecord += listener
            readPrimitiveArrayDumpRecord += listener
            readHeapDumpInfoRecord += listener
          }
          GcRootRecord::class -> {
            readGcRootRecord += listener
          }
          ObjectRecord::class -> {
            readClassDumpRecord += listener
            readInstanceDumpRecord += listener
            readObjectArrayDumpRecord += listener
            readPrimitiveArrayDumpRecord += listener
          }
          ClassDumpRecord::class -> {
            readClassDumpRecord += listener
          }
          InstanceDumpRecord::class -> {
            readInstanceDumpRecord += listener
          }
          ObjectArrayDumpRecord::class -> {
            readObjectArrayDumpRecord += listener
          }
          PrimitiveArrayDumpRecord::class -> {
            readPrimitiveArrayDumpRecord += listener
          }
          HeapDumpInfoRecord::class -> {
            readHeapDumpInfoRecord += listener
          }
        }
      }
    }

    // heap dump timestamp
    skip(LONG_SIZE)

    while (!exhausted()) {
      // type of the record
      val tag = readUnsignedByte()

      // number of microseconds since the time stamp in the header
      skip(INT_SIZE)

      // number of bytes that follow and belong to this record
      val length = readUnsignedInt()

      when (tag) {
        STRING_IN_UTF8 -> {
          if (readStringRecord.isNotEmpty()) {
            val recordPosition = position
            val id = readId()
            val stringLength = length - idSize
            val string = readUtf8(stringLength)
            val record = StringRecord(id, string)
            readStringRecord.forEach { it.onRecord(recordPosition, record) }
          } else {
            skip(length)
          }
        }
        LOAD_CLASS -> {
          if (readLoadClassRecord.isNotEmpty()) {
            val recordPosition = position
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
            readLoadClassRecord.forEach {
              it.onRecord(recordPosition, record)
            }
          } else {
            skip(length)
          }
        }
        STACK_FRAME -> {
          if (readStackFrameRecord.isNotEmpty()) {
            val recordPosition = position
            val record = StackFrameRecord(
                id = readId(),
                methodNameStringId = readId(),
                methodSignatureStringId = readId(),
                sourceFileNameStringId = readId(),
                classSerialNumber = readInt(),
                lineNumber = readInt()
            )
            readStackFrameRecord.forEach {
              it.onRecord(recordPosition, record)
            }
          } else {
            skip(length)
          }
        }
        STACK_TRACE -> {
          if (readStackTraceRecord.isNotEmpty()) {
            val recordPosition = position
            val stackTraceSerialNumber = readInt()
            val threadSerialNumber = readInt()
            val frameCount = readInt()
            val stackFrameIds = readIdArray(frameCount)
            val record = StackTraceRecord(
                stackTraceSerialNumber = stackTraceSerialNumber,
                threadSerialNumber = threadSerialNumber,
                stackFrameIds = stackFrameIds
            )
            readStackTraceRecord.forEach {
              it.onRecord(recordPosition, record)
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
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val record = GcRootRecord(gcRoot = Unknown(id = readId()))
                  readGcRootRecord.forEach { it.onRecord(recordPosition, record) }
                } else {
                  skip(idSize)
                }
              }
              ROOT_JNI_GLOBAL -> {
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val gcRootRecord =
                    GcRootRecord(gcRoot = JniGlobal(id = readId(), jniGlobalRefId = readId()))
                  readGcRootRecord.forEach {
                    it.onRecord(
                        recordPosition, gcRootRecord
                    )
                  }
                } else {
                  skip(idSize + idSize)
                }
              }

              ROOT_JNI_LOCAL -> {
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val gcRootRecord = GcRootRecord(
                      gcRoot = JniLocal(
                          id = readId(), threadSerialNumber = readInt(), frameNumber = readInt()
                      )
                  )
                  readGcRootRecord.forEach {
                    it.onRecord(
                        recordPosition, gcRootRecord
                    )
                  }
                } else {
                  skip(idSize + INT_SIZE + INT_SIZE)
                }
              }

              ROOT_JAVA_FRAME -> {
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val gcRootRecord = GcRootRecord(
                      gcRoot = JavaFrame(
                          id = readId(), threadSerialNumber = readInt(), frameNumber = readInt()
                      )
                  )
                  readGcRootRecord.forEach {
                    it.onRecord(
                        recordPosition, gcRootRecord
                    )
                  }
                } else {
                  skip(idSize + INT_SIZE + INT_SIZE)
                }
              }

              ROOT_NATIVE_STACK -> {
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val gcRootRecord = GcRootRecord(
                      gcRoot = NativeStack(id = readId(), threadSerialNumber = readInt())
                  )
                  readGcRootRecord.forEach {
                    it.onRecord(
                        recordPosition, gcRootRecord
                    )
                  }
                } else {
                  skip(idSize + INT_SIZE)
                }
              }

              ROOT_STICKY_CLASS -> {
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val gcRootRecord = GcRootRecord(
                      gcRoot = StickyClass(id = readId())
                  )
                  readGcRootRecord.forEach {
                    it.onRecord(
                        recordPosition, gcRootRecord
                    )
                  }
                } else {
                  skip(idSize)
                }
              }

              // An object that was referenced from an active thread block.
              ROOT_THREAD_BLOCK -> {
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val gcRootRecord = GcRootRecord(
                      gcRoot = ThreadBlock(id = readId(), threadSerialNumber = readInt())
                  )
                  readGcRootRecord.forEach {
                    it.onRecord(
                        recordPosition, gcRootRecord
                    )
                  }
                } else {
                  skip(idSize + INT_SIZE)
                }
              }

              ROOT_MONITOR_USED -> {
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val gcRootRecord = GcRootRecord(
                      gcRoot = MonitorUsed(id = readId())
                  )
                  readGcRootRecord.forEach {
                    it.onRecord(
                        recordPosition, gcRootRecord
                    )
                  }
                } else {
                  skip(idSize)
                }
              }

              ROOT_THREAD_OBJECT -> {
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val gcRootRecord = GcRootRecord(
                      gcRoot = ThreadObject(
                          id = readId(),
                          threadSerialNumber = readInt(),
                          stackTraceSerialNumber = readInt()
                      )
                  )
                  readGcRootRecord.forEach {
                    it.onRecord(
                        recordPosition, gcRootRecord
                    )
                  }
                } else {
                  skip(idSize + INT_SIZE + INT_SIZE)
                }
              }

              ROOT_INTERNED_STRING -> {
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val gcRootRecord = GcRootRecord(gcRoot = InternedString(id = readId()))
                  readGcRootRecord.forEach {
                    it.onRecord(
                        recordPosition, gcRootRecord
                    )
                  }
                } else {
                  skip(idSize)
                }
              }

              ROOT_FINALIZING -> {
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val gcRootRecord = GcRootRecord(
                      gcRoot = Finalizing(id = readId())
                  )
                  readGcRootRecord.forEach {
                    it.onRecord(
                        recordPosition, gcRootRecord
                    )
                  }
                } else {
                  skip(idSize)
                }
              }

              ROOT_DEBUGGER -> {
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val gcRootRecord = GcRootRecord(
                      gcRoot = Debugger(id = readId())
                  )
                  readGcRootRecord.forEach {
                    it.onRecord(
                        recordPosition, gcRootRecord
                    )
                  }
                } else {
                  skip(idSize)
                }
              }

              ROOT_REFERENCE_CLEANUP -> {
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val gcRootRecord = GcRootRecord(
                      gcRoot = ReferenceCleanup(id = readId())
                  )
                  readGcRootRecord.forEach {
                    it.onRecord(
                        recordPosition, gcRootRecord
                    )
                  }
                } else {
                  skip(idSize)
                }
              }

              ROOT_VM_INTERNAL -> {
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val gcRootRecord = GcRootRecord(
                      gcRoot = VmInternal(id = readId())
                  )
                  readGcRootRecord.forEach {
                    it.onRecord(
                        recordPosition, gcRootRecord
                    )
                  }
                } else {
                  skip(idSize)
                }
              }

              ROOT_JNI_MONITOR -> {
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val gcRootRecord = GcRootRecord(
                      gcRoot = JniMonitor(
                          id = readId(), stackTraceSerialNumber = readInt(),
                          stackDepth = readInt()
                      )
                  )
                  readGcRootRecord.forEach {
                    it.onRecord(
                        recordPosition, gcRootRecord
                    )
                  }
                } else {
                  skip(idSize + INT_SIZE + INT_SIZE)
                }
              }

              ROOT_UNREACHABLE -> {
                if (readGcRootRecord.isNotEmpty()) {
                  val recordPosition = position
                  val gcRootRecord = GcRootRecord(
                      gcRoot = Unreachable(id = readId())
                  )
                  readGcRootRecord.forEach {
                    it.onRecord(
                        recordPosition, gcRootRecord
                    )
                  }
                } else {
                  skip(idSize)
                }
              }
              CLASS_DUMP -> {
                if (readClassDumpRecord.isNotEmpty()) {
                  val recordPosition = position
                  val record = readClassDumpRecord()
                  readClassDumpRecord.forEach {
                    it.onRecord(recordPosition, record)
                  }
                } else {
                  skipClassDumpRecord()
                }
              }

              INSTANCE_DUMP -> {
                if (readInstanceDumpRecord.isNotEmpty()) {
                  val recordPosition = position
                  val instanceDumpRecord = readInstanceDumpRecord()
                  readInstanceDumpRecord.forEach {
                    it.onRecord(recordPosition, instanceDumpRecord)
                  }
                } else {
                  skipInstanceDumpRecord()
                }
              }

              OBJECT_ARRAY_DUMP -> {
                if (readObjectArrayDumpRecord.isNotEmpty()) {
                  val recordPosition = position
                  val arrayRecord = readObjectArrayDumpRecord()
                  readObjectArrayDumpRecord.forEach {
                    it.onRecord(recordPosition, arrayRecord)
                  }
                } else {
                  skipObjectArrayDumpRecord()
                }
              }

              PRIMITIVE_ARRAY_DUMP -> {
                if (readPrimitiveArrayDumpRecord.isNotEmpty()) {
                  val recordPosition = position
                  val record = readPrimitiveArrayDumpRecord()
                  readPrimitiveArrayDumpRecord.forEach {
                    it.onRecord(recordPosition, record)
                  }
                } else {
                  skipPrimitiveArrayDumpRecord()
                }
              }

              PRIMITIVE_ARRAY_NODATA -> {
                throw UnsupportedOperationException("PRIMITIVE_ARRAY_NODATA cannot be parsed")
              }

              HEAP_DUMP_INFO -> {
                if (readHeapDumpInfoRecord.isNotEmpty()) {
                  val recordPosition = position
                  val record = readHeapDumpInfoRecord()
                  readHeapDumpInfoRecord.forEach {
                    it.onRecord(recordPosition, record)
                  }
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
          if (readHeapDumpEnd.isNotEmpty()) {
            val recordPosition = position
            val record = HeapDumpEndRecord
            readHeapDumpEnd.forEach {
              it.onRecord(recordPosition, record)
            }
          }
        }
        else -> {
          skip(length)
        }
      }
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
  }

}