package shark

import shark.HprofRecord.HeapDumpEndRecord
import shark.HprofRecord.HeapDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassSkipContentRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceSkipContentRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArraySkipContentRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArraySkipContentRecord
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StackFrameRecord
import shark.HprofRecord.StringRecord
import java.io.File

/**
 * Converts a Hprof file to another file with deobfuscated class and field names.
 */
class HprofDeobfuscator {

  /**
   * @see HprofDeobfuscator
   */
  fun deobfuscate(
    proguardMappingFile: File,
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

    val proguardMapping =
      ProguardMappingReader(proguardMappingFile.inputStream()).readProguardMapping()

    val hprofStringCache = mutableMapOf<Long, String>()
    val classNames = mutableMapOf<Long, Long>()

    val maxId = readHprofRecords(inputHprofFile, hprofStringCache, classNames)

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
   * @return maximum id value
   */
  private fun readHprofRecords(
    inputHprofFile: File,
    hprofStringCache: MutableMap<Long, String>,
    classNames: MutableMap<Long, Long>
  ): Long {

    var maxId: Long = 0

    Hprof.open(inputHprofFile)
      .use { hprof ->
        hprof.reader.readHprofRecords(setOf(HprofRecord::class),
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
                  is ClassSkipContentRecord -> maxId.coerceAtLeast(record.id)
                  is InstanceDumpRecord -> maxId.coerceAtLeast(record.id)
                  is InstanceSkipContentRecord -> maxId.coerceAtLeast(record.id)
                  is ObjectArrayDumpRecord -> maxId.coerceAtLeast(record.id)
                  is ObjectArraySkipContentRecord -> maxId.coerceAtLeast(record.id)
                  is PrimitiveArrayDumpRecord -> maxId.coerceAtLeast(record.id)
                  is PrimitiveArraySkipContentRecord -> maxId.coerceAtLeast(record.id)
                }
              }
            }
          })
      }

    return maxId
  }

  @Suppress("LongParameterList")
  private fun writeHprofRecords(
    inputHprofFile: File,
    outputHprofFile: File,
    proguardMapping: ProguardMapping,
    hprofStringCache: MutableMap<Long, String>,
    classNames: MutableMap<Long, Long>,
    firstId: Long
  ): File {
    var id = firstId

    Hprof.open(inputHprofFile)
      .use { hprof ->
        val reader = hprof.reader
        HprofWriter.open(
          outputHprofFile,
          identifierByteSize = reader.identifierByteSize,
          hprofVersion = hprof.hprofVersion
        ).use { writer ->
          reader.readHprofRecords(setOf(HprofRecord::class),
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
                is HeapDumpRecord -> {
                  when (record) {
                    is ObjectRecord -> {
                      when (record) {
                        is ClassDumpRecord -> {
                          val recordsToWrite = createDeobfuscatedClassDumpRecord(
                            record, proguardMapping, hprofStringCache, classNames, id
                          )
                          id += recordsToWrite.size
                          recordsToWrite.forEach {
                            writer.write(it)
                          }
                        }
                        else -> writer.write(record)
                      }
                    }
                    else -> writer.write(record)
                  }
                }
                else -> writer.write(record)
              }
            })
            }
      }

    return outputHprofFile
  }

  private fun createDeobfuscatedStringRecord(
    record: StringRecord,
    proguardMapping: ProguardMapping,
    hprofStringCache: MutableMap<Long, String>
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
   * @return a list of HprofRecords to write.
   */
  private fun createDeobfuscatedClassDumpRecord(
    record: ClassDumpRecord,
    proguardMapping: ProguardMapping,
    hprofStringCache: MutableMap<Long, String>,
    classNames: MutableMap<Long, Long>,
    maxId: Long
  ): List<HprofRecord> {
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

    return recordsToWrite
  }
}