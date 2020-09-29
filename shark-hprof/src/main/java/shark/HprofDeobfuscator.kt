package shark

import shark.HprofHeader.Companion.parseHeaderOf
import shark.HprofRecord.HeapDumpEndRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StackFrameRecord
import shark.HprofRecord.StringRecord
import shark.StreamingRecordReaderAdapter.Companion.asStreamingRecordReader
import java.io.File

/**
 * Converts a Hprof file to another file with deobfuscated class and field names.
 */
class HprofDeobfuscator {

  /**
   * @see HprofDeobfuscator
   */
  fun deobfuscate(
    proguardMapping: ProguardMapping,
    inputHprofFile: File,
    /**
     * Optional output file. Defaults to a file in the same directory as [inputHprofFile], with
     * the same name and "-deobfuscated" prepended before the ".hprof" extension. If the file extension
     * is not ".hprof", then "-deobfuscated" is added at the end of the file.
     */
    outputHprofFile: File = File(
        inputHprofFile.parent, inputHprofFile.name.replace(
        ".hprof", "-deobfuscated.hprof"
    ).let { if (it != inputHprofFile.name) it else inputHprofFile.name + "-deobfuscated" })
  ): File {
    val (hprofStringCache, classNames, maxId) = readHprofRecords(inputHprofFile)

    return writeHprofRecords(
        inputHprofFile,
        outputHprofFile,
        proguardMapping,
        hprofStringCache,
        classNames,
        maxId + 1
    )
  }

  /**
   * Reads StringRecords and LoadClassRecord from an Hprof file and tracks maximum HprofRecord id
   * value.
   *
   * @return a Triple of: hprofStringCache map, classNames map and maxId value
   */
  private fun readHprofRecords(
    inputHprofFile: File
  ): Triple<Map<Long, String>, Map<Long, Long>, Long> {
    val hprofStringCache = mutableMapOf<Long, String>()
    val classNames = mutableMapOf<Long, Long>()

    var maxId: Long = 0

    val reader = StreamingHprofReader.readerFor(inputHprofFile).asStreamingRecordReader()
    reader.readRecords(setOf(HprofRecord::class),
        OnHprofRecordListener { _, record ->
          when (record) {
            is StringRecord -> {
              maxId = maxId.coerceAtLeast(record.id)
              hprofStringCache[record.id] = record.string
            }
            is LoadClassRecord -> {
              maxId = maxId.coerceAtLeast(record.id)
              classNames[record.id] = record.classNameStringId
            }
            is StackFrameRecord -> maxId = maxId.coerceAtLeast(record.id)
            is ObjectRecord -> {
              maxId = when (record) {
                is ClassDumpRecord -> maxId.coerceAtLeast(record.id)
                is InstanceDumpRecord -> maxId.coerceAtLeast(record.id)
                is ObjectArrayDumpRecord -> maxId.coerceAtLeast(record.id)
                is PrimitiveArrayDumpRecord -> maxId.coerceAtLeast(record.id)
              }
            }
          }
        })
    return Triple(hprofStringCache, classNames, maxId)
  }

  @Suppress("LongParameterList")
  private fun writeHprofRecords(
    inputHprofFile: File,
    outputHprofFile: File,
    proguardMapping: ProguardMapping,
    hprofStringCache: Map<Long, String>,
    classNames: Map<Long, Long>,
    firstId: Long
  ): File {
    var id = firstId

    val hprofHeader = parseHeaderOf(inputHprofFile)
    val reader =
      StreamingHprofReader.readerFor(inputHprofFile, hprofHeader).asStreamingRecordReader()
    HprofWriter.openWriterFor(
        outputHprofFile,
        hprofHeader = HprofHeader(
            identifierByteSize = hprofHeader.identifierByteSize,
            version = hprofHeader.version
        )
    ).use { writer ->
      reader.readRecords(setOf(HprofRecord::class),
          OnHprofRecordListener { _,
            record ->
            // HprofWriter automatically emits HeapDumpEndRecord, because it flushes
            // all continuous heap dump sub records as one heap dump record.
            if (record is HeapDumpEndRecord) {
              return@OnHprofRecordListener
            }

            when (record) {
              is StringRecord -> {
                writer.write(
                    createDeobfuscatedStringRecord(record, proguardMapping, hprofStringCache)
                )
              }
              is ClassDumpRecord -> {
                val (recordsToWrite, maxId) = createDeobfuscatedClassDumpRecord(
                    record, proguardMapping, hprofStringCache, classNames, id
                )
                id = maxId
                recordsToWrite.forEach {
                  writer.write(it)
                }
              }
              else -> writer.write(record)
            }
          })
    }

    return outputHprofFile
  }

  private fun createDeobfuscatedStringRecord(
    record: StringRecord,
    proguardMapping: ProguardMapping,
    hprofStringCache: Map<Long, String>
  ): StringRecord {
    val obfuscatedName = hprofStringCache[record.id]!!
    return StringRecord(
        record.id, proguardMapping.deobfuscateClassName(obfuscatedName)
    )
  }

  /**
   * Deobfuscated ClassDumpRecord's field names. Different classes can have fields with the same
   * names. We need to generate new StringRecords in such cases.
   *
   * @return a Pair of: list of HprofRecords to write and new maxId value
   */
  private fun createDeobfuscatedClassDumpRecord(
    record: ClassDumpRecord,
    proguardMapping: ProguardMapping,
    hprofStringCache: Map<Long, String>,
    classNames: Map<Long, Long>,
    maxId: Long
  ): Pair<List<HprofRecord>, Long> {
    val recordsToWrite = mutableListOf<HprofRecord>()

    var id = maxId

    val newFields = record.fields.map { field ->
      val className = hprofStringCache[classNames[record.id]]!!
      val fieldName = hprofStringCache[field.nameStringId]!!
      val deobfuscatedName =
        proguardMapping.deobfuscateFieldName(className, fieldName)

      val newStringRecord = StringRecord(id++, deobfuscatedName)
      recordsToWrite.add(newStringRecord)

      FieldRecord(newStringRecord.id, field.type)
    }
    val newStaticFields = record.staticFields.map { field ->
      val className = hprofStringCache[classNames[record.id]]!!
      val fieldName = hprofStringCache[field.nameStringId]!!
      val deobfuscatedName =
        proguardMapping.deobfuscateFieldName(className, fieldName)

      val newStringRecord = StringRecord(id++, deobfuscatedName)
      recordsToWrite.add(newStringRecord)

      StaticFieldRecord(newStringRecord.id, field.type, field.value)
    }

    recordsToWrite.add(
        ClassDumpRecord(
            record.id,
            record.stackTraceSerialNumber,
            record.superclassId,
            record.classLoaderId,
            record.signersId,
            record.protectionDomainId,
            record.instanceSize,
            newStaticFields,
            newFields
        )
    )

    return Pair(recordsToWrite, id)
  }
}