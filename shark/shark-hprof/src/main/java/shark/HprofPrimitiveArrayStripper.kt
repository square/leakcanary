package shark

import java.io.File
import okio.buffer
import okio.sink
import shark.HprofRecordTag.CLASS_DUMP
import shark.HprofRecordTag.HEAP_DUMP
import shark.HprofRecordTag.HEAP_DUMP_INFO
import shark.HprofRecordTag.HEAP_DUMP_SEGMENT
import shark.HprofRecordTag.INSTANCE_DUMP
import shark.HprofRecordTag.LOAD_CLASS
import shark.HprofRecordTag.OBJECT_ARRAY_DUMP
import shark.HprofRecordTag.PRIMITIVE_ARRAY_DUMP
import shark.HprofRecordTag.PRIMITIVE_ARRAY_NODATA
import shark.HprofRecordTag.ROOT_DEBUGGER
import shark.HprofRecordTag.ROOT_FINALIZING
import shark.HprofRecordTag.ROOT_INTERNED_STRING
import shark.HprofRecordTag.ROOT_JAVA_FRAME
import shark.HprofRecordTag.ROOT_JNI_GLOBAL
import shark.HprofRecordTag.ROOT_JNI_LOCAL
import shark.HprofRecordTag.ROOT_JNI_MONITOR
import shark.HprofRecordTag.ROOT_MONITOR_USED
import shark.HprofRecordTag.ROOT_NATIVE_STACK
import shark.HprofRecordTag.ROOT_REFERENCE_CLEANUP
import shark.HprofRecordTag.ROOT_STICKY_CLASS
import shark.HprofRecordTag.ROOT_THREAD_BLOCK
import shark.HprofRecordTag.ROOT_THREAD_OBJECT
import shark.HprofRecordTag.ROOT_UNKNOWN
import shark.HprofRecordTag.ROOT_UNREACHABLE
import shark.HprofRecordTag.ROOT_VM_INTERNAL
import shark.HprofRecordTag.STRING_IN_UTF8
import shark.HprofVersion.ANDROID
import shark.PrimitiveType.BOOLEAN
import shark.PrimitiveType.BYTE
import shark.PrimitiveType.CHAR
import shark.PrimitiveType.DOUBLE
import shark.PrimitiveType.FLOAT
import shark.PrimitiveType.INT
import shark.PrimitiveType.LONG
import shark.PrimitiveType.SHORT

/**
 * Converts a Hprof file to another file with all primitive arrays replaced with arrays of zeroes,
 * which can be useful to remove PII. Char arrays are handled slightly differently because 0 would
 * be the null character so instead these become arrays of '?'.
 *
 * Also updates all primitive wrapper instances to wrap 0 instead of their actual value, as an
 * additional safety measure.
 */
class HprofPrimitiveArrayStripper {

  /** @see HprofPrimitiveArrayStripper */
  fun stripPrimitiveArrays(
    inputHprofFile: File,
    /**
     * Optional output file. Defaults to a file in the same directory as [inputHprofFile], with the
     * same name and "-stripped" prepended before the ".hprof" extension. If the file extension is
     * not ".hprof", then "-stripped" is added at the end of the file.
     */
    outputHprofFile: File =
      File(
        inputHprofFile.parent,
        inputHprofFile.name.replace(".hprof", "-stripped.hprof").let {
          if (it != inputHprofFile.name) {
            it
          } else {
            inputHprofFile.name + "-stripped"
          }
        },
      ),
    deleteInputHprofFile: Boolean = false
  ): File {
    stripPrimitiveArrays(
      hprofSourceProvider = FileSourceProvider(inputHprofFile),
      hprofSinkProvider = { outputHprofFile.outputStream().sink().buffer() },
      onDoneOpeningNewSources = {
        if (deleteInputHprofFile) {
          // Using the Unix trick of deleting the file as soon as all readers have opened it.
          // No new readers/writers will be able to access the file, but all existing
          // ones will still have access until the last one closes the file.
          SharkLog.d { "Deleting $inputHprofFile eagerly" }
          inputHprofFile.delete()
        }
      },
    )
    return outputHprofFile
  }

  /** @see HprofPrimitiveArrayStripper */
  fun stripPrimitiveArrays(
    hprofSourceProvider: StreamingSourceProvider,
    hprofSinkProvider: StreamingSinkProvider,
    onDoneOpeningNewSources: () -> Unit = {}
  ) {
    hprofSourceProvider.openStreamingSource().use { rawSource ->
      onDoneOpeningNewSources()
      hprofSinkProvider.openStreamingSink().use { sink ->
        val source = CopyingSource(rawSource, sink)
        stripPrimitiveArrays(source)
      }
    }
  }

  private fun stripPrimitiveArrays(source: CopyingSource) {
    val endOfString = source.indexOf(0)
    val versionName = source.transferUtf8(endOfString)
    // Skip the 0 at the end of the version string.
    source.transfer(1)
    val identifierByteSize = source.transferInt()
    // heapDumpTimestamp
    source.transfer(LONG.byteSize)

    val useForwardSlashClassPackageSeparator = versionName != ANDROID.versionString
    val primitiveWrapperClassNameAndValueSizes =
      mapOf(
          Boolean::class.javaObjectType.name to BOOLEAN.byteSize,
          Char::class.javaObjectType.name to CHAR.byteSize,
          Float::class.javaObjectType.name to FLOAT.byteSize,
          Double::class.javaObjectType.name to DOUBLE.byteSize,
          Byte::class.javaObjectType.name to BYTE.byteSize,
          Short::class.javaObjectType.name to SHORT.byteSize,
          Int::class.javaObjectType.name to INT.byteSize,
          Long::class.javaObjectType.name to LONG.byteSize,
        )
        .mapKeys { (key, _) ->
          if (useForwardSlashClassPackageSeparator) {
            key.replace('.', '/')
          } else {
            key
          }
        }

    val typeSizesRawMap =
      PrimitiveType.byteSizeByHprofType + (PrimitiveType.REFERENCE_HPROF_TYPE to identifierByteSize)

    val maxKey = typeSizesRawMap.keys.max()

    // An efficient map of type to size. Some entries aren't used.
    val typeSizes = IntArray(maxKey + 1) { key -> typeSizesRawMap[key] ?: 0 }

    fun CopyingSource.transferId(): Long {
      // As long as we don't interpret IDs, reading signed values here is fine.
      return when (identifierByteSize) {
        1 -> transferByte().toLong()
        2 -> transferShort().toLong()
        4 -> transferInt().toLong()
        8 -> transferLong()
        else -> throw IllegalArgumentException("ID Length must be 1, 2, 4, or 8")
      }
    }

    // Local ref optimizations
    val intByteSize = INT.byteSize

    val primitiveWrapperStringIdsWithValueSize = mutableMapOf<Long, Int>()
    val primitiveWrapperClassIdsWithValueSize = mutableMapOf<Long, Int>()
    val primitiveWrapperClassValueFields = mutableMapOf<Long, PrimitiveWrapperValueField>()
    var valueStringId = 0L

    while (!source.exhausted()) {
      // type of the record
      val tag = source.transferUnsignedByte()

      // Int, number of microseconds since the time stamp in the header
      source.transfer(intByteSize)

      // number of bytes that follow and belong to this record
      val length = source.transferUnsignedInt()
      val bytesReadBeforeTagContent = source.bytesRead

      when (tag) {
        STRING_IN_UTF8.tag -> {
          val id = source.transferId()
          val byteCount = length - identifierByteSize
          val string = source.transferUtf8(byteCount)
          val size = primitiveWrapperClassNameAndValueSizes[string]
          if (size != null) {
            primitiveWrapperStringIdsWithValueSize[id] = size
          } else if (string == "value") {
            valueStringId = id
          }
        }

        LOAD_CLASS.tag -> {
          // classSerialNumber
          source.transfer(intByteSize)
          val id = source.transferId()
          // stackTraceSerialNumber
          source.transfer(intByteSize)
          val classNameStringId = source.transferId()
          val size = primitiveWrapperStringIdsWithValueSize[classNameStringId]
          if (size != null) {
            primitiveWrapperClassIdsWithValueSize[id] = size
          }
        }

        HEAP_DUMP.tag,
        HEAP_DUMP_SEGMENT.tag -> {
          var previousTag = 0
          var previousTagPosition = 0L
          val bytesReadStart = source.bytesRead
          while ((source.bytesRead - bytesReadStart) < length) {
            val heapDumpTagPosition = source.bytesRead
            val heapDumpTag = source.transferUnsignedByte()
            when (heapDumpTag) {
              ROOT_UNKNOWN.tag,
              ROOT_STICKY_CLASS.tag,
              ROOT_MONITOR_USED.tag,
              ROOT_INTERNED_STRING.tag,
              ROOT_FINALIZING.tag,
              ROOT_DEBUGGER.tag,
              ROOT_REFERENCE_CLEANUP.tag,
              ROOT_VM_INTERNAL.tag,
              ROOT_UNREACHABLE.tag -> {
                source.transfer(identifierByteSize)
              }

              ROOT_JNI_GLOBAL.tag -> {
                source.transfer(identifierByteSize + identifierByteSize)
              }

              ROOT_JNI_LOCAL.tag,
              ROOT_JAVA_FRAME.tag,
              ROOT_THREAD_OBJECT.tag,
              ROOT_JNI_MONITOR.tag -> {
                source.transfer(identifierByteSize + intByteSize + intByteSize)
              }

              ROOT_NATIVE_STACK.tag,
              ROOT_THREAD_BLOCK.tag -> {
                source.transfer(identifierByteSize + intByteSize)
              }

              CLASS_DUMP.tag -> {
                val id = source.transferId()

                val size = primitiveWrapperClassIdsWithValueSize[id]

                val byteSize =
                  // stack trace serial number
                  intByteSize +
                    // superclassId
                    identifierByteSize +
                    // class loader object ID
                    identifierByteSize +
                    // signers object ID
                    identifierByteSize +
                    // protection domain object ID
                    identifierByteSize +
                    // reserved
                    identifierByteSize +
                    // reserved
                    identifierByteSize +
                    // instance size (in bytes)
                    intByteSize
                source.transfer(byteSize)

                // Skip over the constant pool
                val constantPoolCount = source.transferUnsignedShort()
                repeat(constantPoolCount) {
                  // constant pool index
                  source.transfer(SHORT.byteSize)
                  val type = source.transferUnsignedByte()
                  val byteCount = typeSizes[type]
                  source.transfer(byteCount)
                }

                val staticFieldCount = source.transferUnsignedShort()
                repeat(staticFieldCount) {
                  // nameStringId
                  source.transfer(identifierByteSize)
                  val type = source.transferUnsignedByte()
                  val byteCount = typeSizes[type]
                  source.transfer(byteCount)
                }

                val fieldCount = source.transferUnsignedShort()
                var fieldPosition = 0
                repeat(fieldCount) {
                  val nameStringId = source.transferId()
                  val type = source.transferUnsignedByte()
                  if (size != null && nameStringId == valueStringId) {
                    primitiveWrapperClassValueFields[id] =
                      PrimitiveWrapperValueField(fieldPosition, size)
                  }
                  fieldPosition = typeSizes[type]
                }
              }

              INSTANCE_DUMP.tag -> {
                source.transfer(
                  // id
                  identifierByteSize +
                    // stackTraceSerialNumber
                    intByteSize
                )

                val classId = source.transferId()
                val wrapperClassValueField = primitiveWrapperClassValueFields[classId]

                val remainingBytesInInstance = source.transferInt()

                if (wrapperClassValueField != null) {
                  source.transfer(wrapperClassValueField.position)
                  source.overwrite(ByteArray(wrapperClassValueField.byteSize))
                  val written = wrapperClassValueField.byteSize + wrapperClassValueField.position
                  val remaining = remainingBytesInInstance - written
                  source.transfer(remaining)
                } else {
                  source.transfer(remainingBytesInInstance)
                }
              }

              OBJECT_ARRAY_DUMP.tag -> {
                source.transfer(
                  // id
                  identifierByteSize +
                    // stackTraceSerialNumber
                    intByteSize
                )
                val arrayLength = source.transferInt()
                // arrayClassId
                source.transfer(identifierByteSize)
                source.transfer(arrayLength * identifierByteSize)
              }

              PRIMITIVE_ARRAY_DUMP.tag -> {
                source.transfer(identifierByteSize + intByteSize)
                val arrayLength = source.transferInt()
                val type = source.transferUnsignedByte()
                when (val primitiveType = PrimitiveType.primitiveTypeByHprofType.getValue(type)) {
                  CHAR -> {
                    val byteArray =
                      String(CharArray(arrayLength) { '?' }).toByteArray(Charsets.UTF_16BE)
                    source.overwrite(byteArray)
                  }

                  BYTE -> {
                    source.overwrite(
                      ByteArray(arrayLength) {
                        // Strings are stored as byte arrays and we can't distinguish between those
                        // and random byte arrays, so we're updating all byte arrays the same way.
                        // Converts to '?' in UTF-8 for byte backed strings
                        63
                      }
                    )
                  }

                  else -> {
                    source.overwrite(ByteArray(arrayLength * primitiveType.byteSize) { 0 })
                  }
                }
              }

              PRIMITIVE_ARRAY_NODATA.tag -> {
                throw UnsupportedOperationException("$PRIMITIVE_ARRAY_NODATA cannot be parsed")
              }

              HEAP_DUMP_INFO.tag -> {
                source.transfer(identifierByteSize + identifierByteSize)
              }

              else ->
                throw IllegalStateException(
                  "Unknown tag ${
                  "0x%02x".format(
                    heapDumpTag
                  )
                } at $heapDumpTagPosition after ${
                  "0x%02x".format(
                    previousTag
                  )
                } at $previousTagPosition, heap dump segment started at $bytesReadStart, " +
                    "length $length, ${(bytesReadStart + length) - heapDumpTagPosition} remaining"
                )
            }
            previousTag = heapDumpTag
            previousTagPosition = heapDumpTagPosition
          }
        }

        else -> {
          if (length > 0) {
            source.transfer(length)
          }
        }
      }

      check(bytesReadBeforeTagContent + length == source.bytesRead) {
        "Started tag content at $bytesReadBeforeTagContent, " +
          "expected to read $length bytes, " +
          "ended up at ${source.bytesRead} " +
          "reading ${source.bytesRead - bytesReadBeforeTagContent} bytes"
      }
    }
  }

  private class PrimitiveWrapperValueField(val position: Int, val byteSize: Int)
}
