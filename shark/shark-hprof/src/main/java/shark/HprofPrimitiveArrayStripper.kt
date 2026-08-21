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
 *
 * Everything else is copied over unchanged, which includes the string records holding the class,
 * field and method names the rest of the heap dump refers to. Those hold no runtime data in a heap
 * dump from Android, but a heap dump from a JVM also holds every string constant of every loaded
 * class in them, so stripping a JVM heap dump leaves the constants written in the code behind.
 */
class HprofPrimitiveArrayStripper {

  /**
   * [inputHprofFile] is read gzipped when its content is gzipped, and [outputHprofFile] is written
   * gzipped when its name ends with ".gz", so stripping "app.hprof.gz" writes a gzipped
   * "app-stripped.hprof.gz" by default.
   *
   * @see HprofPrimitiveArrayStripper
   */
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
    val fileSinkProvider = StreamingSinkProvider {
      outputHprofFile.outputStream().sink().buffer()
    }
    stripPrimitiveArrays(
      hprofSourceProvider = FileSourceProvider(inputHprofFile).gunzipIfGzipped(),
      hprofSinkProvider =
        if (outputHprofFile.name.endsWith(GZIP_FILE_EXTENSION)) {
          fileSinkProvider.gzip()
        } else {
          fileSinkProvider
        },
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
    val primitiveWrapperValueTypesByClassName =
      mapOf(
          Boolean::class.javaObjectType.name to BOOLEAN,
          Char::class.javaObjectType.name to CHAR,
          Float::class.javaObjectType.name to FLOAT,
          Double::class.javaObjectType.name to DOUBLE,
          Byte::class.javaObjectType.name to BYTE,
          Short::class.javaObjectType.name to SHORT,
          Int::class.javaObjectType.name to INT,
          Long::class.javaObjectType.name to LONG,
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

    val primitiveWrapperClassesByNameStringId = PrimitiveWrapperClassesById()
    val primitiveWrapperClassesByClassId = PrimitiveWrapperClassesById()
    var startedReadingHeapDump = false

    // Arrays are replaced by repeating one of these over their content, so that replacing an array
    // never needs a buffer the size of that array.
    val zeroes = ByteArray(REPLACEMENT_PATTERN_BYTE_SIZE)
    val utf8QuestionMarks = ByteArray(REPLACEMENT_PATTERN_BYTE_SIZE) { QUESTION_MARK_BYTE }
    val utf16BeQuestionMarks =
      ByteArray(REPLACEMENT_PATTERN_BYTE_SIZE) { index ->
        if (index % 2 == 0) 0 else QUESTION_MARK_BYTE
      }

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
          val valueType = primitiveWrapperValueTypesByClassName[string]
          if (valueType != null) {
            primitiveWrapperClassesByNameStringId[id] = PrimitiveWrapperClass(string, valueType)
          }
        }

        LOAD_CLASS.tag -> {
          // classSerialNumber
          source.transfer(intByteSize)
          val id = source.transferId()
          // stackTraceSerialNumber
          source.transfer(intByteSize)
          val classNameStringId = source.transferId()
          val wrapperClass = primitiveWrapperClassesByNameStringId[classNameStringId]
          if (wrapperClass != null) {
            check(!startedReadingHeapDump) {
              "${wrapperClass.className} is loaded by a $LOAD_CLASS record that comes after the " +
                "start of the heap dump. Zeroing out the value a primitive wrapper instance wraps " +
                "needs the class id of the wrapper, which only that record provides, so any " +
                "instance dumped before it would keep its value in the stripped heap dump. " +
                "Please report this heap dump to https://github.com/square/leakcanary/issues"
            }
            primitiveWrapperClassesByClassId[id] = wrapperClass
          }
        }

        HEAP_DUMP.tag,
        HEAP_DUMP_SEGMENT.tag -> {
          startedReadingHeapDump = true
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

                val wrapperClass = primitiveWrapperClassesByClassId[id]

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
                var firstFieldType = 0
                repeat(fieldCount) { fieldIndex ->
                  // nameStringId
                  source.transfer(identifierByteSize)
                  val type = source.transferUnsignedByte()
                  if (fieldIndex == 0) {
                    firstFieldType = type
                  }
                }

                if (wrapperClass != null) {
                  val valueType = wrapperClass.valueType
                  check(fieldCount == 1 && firstFieldType == valueType.hprofType) {
                    "Expected ${wrapperClass.className} to declare a single instance field of type " +
                      "$valueType, found $fieldCount field(s) starting with hprof type " +
                      "$firstFieldType. Stripping zeroes out the value a primitive wrapper wraps by " +
                      "writing over the start of its instance field values, which is the wrong " +
                      "place for this layout, so the wrapped values would be left in the stripped " +
                      "heap dump. Please report this heap dump to " +
                      "https://github.com/square/leakcanary/issues"
                  }
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
                val wrapperClass = primitiveWrapperClassesByClassId[classId]

                val remainingBytesInInstance = source.transferInt()

                if (wrapperClass != null) {
                  // The value a primitive wrapper wraps is the only instance field it declares, and
                  // a class declares its own fields ahead of the ones it inherits, so the value is
                  // at the start of the instance field values.
                  val valueByteSize = wrapperClass.valueType.byteSize
                  source.overwriteRepeating(valueByteSize.toLong(), zeroes)
                  source.transfer(remainingBytesInInstance - valueByteSize)
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
                source.transfer(arrayLength.toLong() * identifierByteSize)
              }

              PRIMITIVE_ARRAY_DUMP.tag -> {
                source.transfer(identifierByteSize + intByteSize)
                val arrayLength = source.transferInt()
                val type = source.transferUnsignedByte()
                val primitiveType = PrimitiveType.primitiveTypeByHprofType.getValue(type)
                val replacement =
                  when (primitiveType) {
                    // Strings are stored as byte arrays and we can't distinguish between those and
                    // random byte arrays, so we're updating all byte arrays the same way.
                    BYTE -> utf8QuestionMarks
                    CHAR -> utf16BeQuestionMarks
                    else -> zeroes
                  }
                source.overwriteRepeating(
                  byteCount = arrayLength.toLong() * primitiveType.byteSize,
                  pattern = replacement,
                )
              }

              PRIMITIVE_ARRAY_NODATA.tag -> {
                throw UnsupportedOperationException("$PRIMITIVE_ARRAY_NODATA cannot be parsed")
              }

              HEAP_DUMP_INFO.tag -> {
                // heapId, then heapNameStringId. The heap id is an Int, not an object id.
                source.transfer(intByteSize + identifierByteSize)
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

  private class PrimitiveWrapperClass(
    val className: String,
    val valueType: PrimitiveType
  )

  /**
   * The primitive wrapper classes found so far, looked up by an id read from the heap dump.
   *
   * Scanning an array of ids instead of hashing them matters here: looking a class id up in a [Map]
   * keyed by [Long] boxes the class id of every instance in the heap dump, which is hundreds of
   * megabytes of garbage on a large one. There's one primitive wrapper class per [PrimitiveType],
   * so a heap dump holds 8 ids to scan and they fit in a single cache line.
   */
  private class PrimitiveWrapperClassesById {
    private val ids = LongArray(PrimitiveType.values().size)
    private val wrapperClasses = arrayOfNulls<PrimitiveWrapperClass>(ids.size)
    private var size = 0

    operator fun set(
      id: Long,
      wrapperClass: PrimitiveWrapperClass
    ) {
      check(size < ids.size) {
        "Found ${size + 1} ids for the primitive wrapper classes, the last of them $id for " +
          "${wrapperClass.className}, when there is one wrapper class per primitive type and " +
          "therefore at most ${ids.size} of them to find. Getting here takes a heap dump that " +
          "holds the name of a wrapper class in two string records, or that loads one of those " +
          "classes twice, and no runtime writes either: they dedupe the strings they dump, and " +
          "these classes are loaded by the bootstrap class loader, once. Please report this heap " +
          "dump to https://github.com/square/leakcanary/issues"
      }
      ids[size] = id
      wrapperClasses[size] = wrapperClass
      size++
    }

    operator fun get(id: Long): PrimitiveWrapperClass? {
      for (index in 0 until size) {
        if (ids[index] == id) {
          return wrapperClasses[index]
        }
      }
      return null
    }
  }
}

/**
 * Even, so that repeating a pattern of that size always lands on whole UTF-16 characters, and equal
 * to the size of an Okio segment.
 */
private const val REPLACEMENT_PATTERN_BYTE_SIZE = 8192

/** '?', in UTF-8 and in the low byte of a UTF-16BE character alike. */
private const val QUESTION_MARK_BYTE: Byte = 63

private const val GZIP_FILE_EXTENSION = ".gz"
