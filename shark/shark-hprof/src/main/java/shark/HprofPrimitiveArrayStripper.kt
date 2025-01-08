package shark

import java.io.File
import okio.BufferedSink
import okio.Okio
import shark.HprofRecord.HeapDumpEndRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StringRecord
import shark.HprofVersion.ANDROID
import shark.PrimitiveType.BOOLEAN
import shark.PrimitiveType.BYTE
import shark.PrimitiveType.CHAR
import shark.PrimitiveType.DOUBLE
import shark.PrimitiveType.FLOAT
import shark.PrimitiveType.INT
import shark.PrimitiveType.LONG
import shark.PrimitiveType.SHORT
import shark.StreamingRecordReaderAdapter.Companion.asStreamingRecordReader

/**
 * Converts a Hprof file to another file with all primitive arrays replaced with arrays of zeroes,
 * which can be useful to remove PII. Char arrays are handled slightly differently because 0 would
 * be the null character so instead these become arrays of '?'.
 *
 * Also updates all primitive wrapper instances to wrap 0 instead of their actual value, as
 * an additional safety measure.
 */
class HprofPrimitiveArrayStripper {

  /**
   * @see HprofPrimitiveArrayStripper
   */
  fun stripPrimitiveArrays(
    inputHprofFile: File,
    /**
     * Optional output file. Defaults to a file in the same directory as [inputHprofFile], with
     * the same name and "-stripped" prepended before the ".hprof" extension. If the file extension
     * is not ".hprof", then "-stripped" is added at the end of the file.
     */
    outputHprofFile: File = File(
      inputHprofFile.parent, inputHprofFile.name.replace(
      ".hprof", "-stripped.hprof"
    ).let { if (it != inputHprofFile.name) it else inputHprofFile.name + "-stripped" })
  ): File {
    stripPrimitiveArrays(
      hprofSourceProvider = FileSourceProvider(inputHprofFile),
      hprofSink = Okio.buffer(Okio.sink(outputHprofFile.outputStream()))
    )
    return outputHprofFile
  }

  /**
   * @see HprofPrimitiveArrayStripper
   */
  fun stripPrimitiveArrays(
    hprofSourceProvider: StreamingSourceProvider,
    hprofSink: BufferedSink
  ) {
    val header = hprofSourceProvider.openStreamingSource().use { HprofHeader.parseHeaderOf(it) }
    val useForwardSlashClassPackageSeparator = header.version != ANDROID

    val primitiveWrapperClassNames = mapOf(
      Boolean::class.javaObjectType.name to BOOLEAN_SIZE,
      Char::class.javaObjectType.name to CHAR_SIZE,
      Float::class.javaObjectType.name to FLOAT_SIZE,
      Double::class.javaObjectType.name to DOUBLE_SIZE,
      Byte::class.javaObjectType.name to BYTE_SIZE,
      Short::class.javaObjectType.name to SHORT_SIZE,
      Int::class.javaObjectType.name to INT_SIZE,
      Long::class.javaObjectType.name to LONG_SIZE
    ).mapKeys { (key, _) ->
      if (useForwardSlashClassPackageSeparator) {
        key.replace('.', '/')
      } else {
        key
      }
    }

    val reader =
      StreamingHprofReader.readerFor(hprofSourceProvider, header).asStreamingRecordReader()
    HprofWriter.openWriterFor(
      hprofSink,
      hprofHeader = header
    )
      .use { writer ->
        val primitiveWrapperStringIdsWithValueSize = mutableMapOf<Long, Int>()
        val primitiveWrapperClassIdsWithValueSize = mutableMapOf<Long, Int>()
        val primitiveWrapperClassValueFields = mutableMapOf<Long, PrimitiveWrapperValueField>()
        var valueStringId = 0L
        reader.readRecords(setOf(HprofRecord::class),
          OnHprofRecordListener { _, record ->
            // HprofWriter automatically emits HeapDumpEndRecord, because it flushes
            // all continuous heap dump sub records as one heap dump record.
            if (record is HeapDumpEndRecord) {
              return@OnHprofRecordListener
            }
            writer.write(
              when (record) {
                is BooleanArrayDump -> BooleanArrayDump(
                  record.id, record.stackTraceSerialNumber,
                  BooleanArray(record.array.size)
                )

                is CharArrayDump -> CharArrayDump(
                  record.id, record.stackTraceSerialNumber,
                  CharArray(record.array.size) {
                    '?'
                  }
                )

                is FloatArrayDump -> FloatArrayDump(
                  record.id, record.stackTraceSerialNumber,
                  FloatArray(record.array.size)
                )

                is DoubleArrayDump -> DoubleArrayDump(
                  record.id, record.stackTraceSerialNumber,
                  DoubleArray(record.array.size)
                )

                is ByteArrayDump -> ByteArrayDump(
                  record.id, record.stackTraceSerialNumber,
                  ByteArray(record.array.size) {
                    // Converts to '?' in UTF-8 for byte backed strings
                    63
                  }
                )

                is ShortArrayDump -> ShortArrayDump(
                  record.id, record.stackTraceSerialNumber,
                  ShortArray(record.array.size)
                )

                is IntArrayDump -> IntArrayDump(
                  record.id, record.stackTraceSerialNumber,
                  IntArray(record.array.size)
                )

                is LongArrayDump -> LongArrayDump(
                  record.id, record.stackTraceSerialNumber,
                  LongArray(record.array.size)
                )

                is StringRecord -> {
                  val size = primitiveWrapperClassNames[record.string]
                  if (size != null) {
                    primitiveWrapperStringIdsWithValueSize[record.id] = size
                  } else if (record.string == "value") {
                    valueStringId = record.id
                  }
                  record
                }

                is LoadClassRecord -> {
                  val size = primitiveWrapperStringIdsWithValueSize[record.classNameStringId]
                  if (size != null) {
                    primitiveWrapperClassIdsWithValueSize[record.id] = size
                  }
                  record
                }

                is ClassDumpRecord -> {
                  val size = primitiveWrapperClassIdsWithValueSize[record.id]
                  if (size != null) {
                    var valuePosition = 0
                    record.fields.forEach { fieldRecord ->
                      if (fieldRecord.nameStringId == valueStringId) {
                        return@forEach
                      } else {
                        valuePosition += when (fieldRecord.type) {
                          PrimitiveType.REFERENCE_HPROF_TYPE -> header.identifierByteSize
                          BOOLEAN_TYPE -> BOOLEAN_SIZE
                          CHAR_TYPE -> CHAR_SIZE
                          FLOAT_TYPE -> FLOAT_SIZE
                          DOUBLE_TYPE -> DOUBLE_SIZE
                          BYTE_TYPE -> BYTE_SIZE
                          SHORT_TYPE -> SHORT_SIZE
                          INT_TYPE -> INT_SIZE
                          LONG_TYPE -> LONG_SIZE
                          else -> error("Unexpected field record type ${fieldRecord.type}")
                        }
                      }
                    }
                    primitiveWrapperClassValueFields[record.id] =
                      PrimitiveWrapperValueField(valuePosition, size)
                  }
                  record
                }

                is InstanceDumpRecord -> {
                  val wrapperClassValueField = primitiveWrapperClassValueFields[record.classId]
                  if (wrapperClassValueField != null) {
                    for (i in wrapperClassValueField.range) {
                      record.fieldValues[i] = 0
                    }
                  }
                  record
                }

                else -> {
                  record
                }
              }
            )
          })
      }
  }

  private class PrimitiveWrapperValueField(
    position: Int,
    size: Int
  ) {
    val range: IntRange = position until (position + size)
  }

  private companion object {
    private val BOOLEAN_SIZE = BOOLEAN.byteSize
    private val CHAR_SIZE = CHAR.byteSize
    private val FLOAT_SIZE = FLOAT.byteSize
    private val DOUBLE_SIZE = DOUBLE.byteSize
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
  }
}
