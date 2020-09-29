package shark

import okio.BufferedSink
import okio.Okio
import shark.HprofRecord.HeapDumpEndRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import shark.StreamingRecordReaderAdapter.Companion.asStreamingRecordReader
import java.io.File

/**
 * Converts a Hprof file to another file with all primitive arrays replaced with arrays of zeroes,
 * which can be useful to remove PII. Char arrays are handled slightly differently because 0 would
 * be the null character so instead these become arrays of '?'.
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
    val reader =
      StreamingHprofReader.readerFor(hprofSourceProvider, header).asStreamingRecordReader()
    HprofWriter.openWriterFor(
        hprofSink,
        hprofHeader = HprofHeader(
            identifierByteSize = header.identifierByteSize,
            version = header.version
        )
    )
        .use { writer ->
          reader.readRecords(setOf(HprofRecord::class),
              OnHprofRecordListener { _,
                record ->
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
                          ByteArray(record.array.size)
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
                      else -> {
                        record
                      }
                    }
                )
              })
        }
  }

}