package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofRecord.HeapDumpEndRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StringRecord
import shark.StreamingRecordReaderAdapter.Companion.asStreamingRecordReader
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder

class HprofWriterTest {

  private var lastId = 0L
  private val id: Long
    get() = ++lastId

  @Test
  fun writeAndReadStringRecord() {
    val record = StringRecord(id, MAGIC_WAND_CLASS_NAME)
    val bytes = listOf(record).asHprofBytes()

    val readRecords = bytes.readAllRecords()

    assertThat(readRecords).hasSize(1)
    assertThat(readRecords[0]).isInstanceOf(StringRecord::class.java)
    assertThat((readRecords[0] as StringRecord).id).isEqualTo(record.id)
    assertThat((readRecords[0] as StringRecord).string).isEqualTo(record.string)
  }

  @Test
  fun writeAndReadClassRecord() {
    val className = StringRecord(id, MAGIC_WAND_CLASS_NAME)
    val loadClassRecord = LoadClassRecord(1, id, 1, className.id)
    val classDump = ClassDumpRecord(
        id = loadClassRecord.id,
        stackTraceSerialNumber = 1,
        superclassId = 0,
        classLoaderId = 0,
        signersId = 0,
        protectionDomainId = 0,
        instanceSize = 0,
        staticFields = emptyList(),
        fields = emptyList()
    )

    val bytes = listOf(className, loadClassRecord, classDump).asHprofBytes()
    bytes.openHeapGraph().use { graph: HeapGraph ->
      assertThat(graph.findClassByName(className.string)).isNotNull
    }
  }

  @Test
  fun writeAndReadStaticField() {
    val className = StringRecord(id, MAGIC_WAND_CLASS_NAME)
    val field1Name = StringRecord(id, "field1")
    val field2Name = StringRecord(id, "field2")
    val loadClassRecord = LoadClassRecord(1, id, 1, className.id)
    val classDump = ClassDumpRecord(
        id = loadClassRecord.id,
        stackTraceSerialNumber = 1,
        superclassId = 0,
        classLoaderId = 0,
        signersId = 0,
        protectionDomainId = 0,
        instanceSize = 0,
        staticFields = listOf(
            StaticFieldRecord(field1Name.id, PrimitiveType.BOOLEAN.hprofType, BooleanHolder(true)),
            StaticFieldRecord(field2Name.id, PrimitiveType.INT.hprofType, IntHolder(42))
        ),
        fields = emptyList()
    )
    val bytes = listOf(className, field1Name, field2Name, loadClassRecord, classDump)
        .asHprofBytes()
    bytes.openHeapGraph().use { graph: HeapGraph ->
      val heapClass = graph.findClassByName(className.string)!!
      val staticFields = heapClass.readStaticFields().toList()
      assertThat(staticFields).hasSize(2)
      assertThat(staticFields[0].name).isEqualTo(field1Name.string)
      assertThat(staticFields[0].value.asBoolean).isEqualTo(true)
      assertThat(staticFields[1].name).isEqualTo(field2Name.string)
      assertThat(staticFields[1].value.asInt).isEqualTo(42)
    }
  }

  @Test
  fun writeAndReadHprof() {
    val records = createRecords()

    val bytes = records.asHprofBytes()

    val readRecords = bytes.readAllRecords()
    assertThat(readRecords).hasSameSizeAs(records + HeapDumpEndRecord)

    bytes.openHeapGraph().use { graph: HeapGraph ->
      val treasureChestClass = graph.findClassByName(
          TREASURE_CHEST_CLASS_NAME
      )!!
      val baguetteInstance =
        treasureChestClass[CONTENT_FIELD_NAME]!!.value.asObject!!.asInstance!!

      assertThat(
          baguetteInstance[BAGUETTE_CLASS_NAME, ANSWER_FIELD_NAME]!!.value.asInt!!
      ).isEqualTo(42)
    }
  }

  private fun createRecords(): List<HprofRecord> {
    val magicWandClassName = StringRecord(id, MAGIC_WAND_CLASS_NAME)
    val baguetteClassName = StringRecord(id, BAGUETTE_CLASS_NAME)
    val answerFieldName = StringRecord(id, ANSWER_FIELD_NAME)
    val treasureChestClassName = StringRecord(
        id,
        TREASURE_CHEST_CLASS_NAME
    )
    val contentFieldName = StringRecord(id, CONTENT_FIELD_NAME)
    val loadMagicWandClass = LoadClassRecord(1, id, 1, magicWandClassName.id)
    val loadBaguetteClass = LoadClassRecord(1, id, 1, baguetteClassName.id)
    val loadTreasureChestClass = LoadClassRecord(1, id, 1, treasureChestClassName.id)
    val magicWandClassDump = ClassDumpRecord(
        id = loadMagicWandClass.id,
        stackTraceSerialNumber = 1,
        superclassId = 0,
        classLoaderId = 0,
        signersId = 0,
        protectionDomainId = 0,
        instanceSize = 0,
        staticFields = emptyList(),
        fields = emptyList()
    )
    val baguetteClassDump = ClassDumpRecord(
        id = loadBaguetteClass.id,
        stackTraceSerialNumber = 1,
        superclassId = loadMagicWandClass.id,
        classLoaderId = 0,
        signersId = 0,
        protectionDomainId = 0,
        instanceSize = 0,
        staticFields = emptyList(),
        fields = listOf(FieldRecord(answerFieldName.id, PrimitiveType.INT.hprofType))
    )

    val baguetteInstanceDump = InstanceDumpRecord(
        id = id,
        stackTraceSerialNumber = 1,
        classId = loadBaguetteClass.id,
        fieldValues = byteArrayOf(0x0, 0x0, 0x0, 0x2a)
    )

    val treasureChestClassDump = ClassDumpRecord(
        id = loadTreasureChestClass.id,
        stackTraceSerialNumber = 1,
        superclassId = 0,
        classLoaderId = 0,
        signersId = 0,
        protectionDomainId = 0,
        instanceSize = 0,
        staticFields = listOf(
            StaticFieldRecord(
                contentFieldName.id, PrimitiveType.REFERENCE_HPROF_TYPE,
                ReferenceHolder(baguetteInstanceDump.id)
            )
        ),
        fields = emptyList()
    )

    return listOf(
        magicWandClassName, baguetteClassName, answerFieldName, treasureChestClassName,
        contentFieldName, loadMagicWandClass,
        loadBaguetteClass, loadTreasureChestClass,
        magicWandClassDump, baguetteClassDump, baguetteInstanceDump, treasureChestClassDump
    )
  }

  private fun DualSourceProvider.readAllRecords(): MutableList<HprofRecord> {
    val readRecords = mutableListOf<HprofRecord>()
    StreamingHprofReader.readerFor(this).asStreamingRecordReader()
        .readRecords(setOf(HprofRecord::class), OnHprofRecordListener { position, record ->
          readRecords += record
        })
    return readRecords
  }

  companion object {
    const val MAGIC_WAND_CLASS_NAME = "com.example.MagicWand"
    const val BAGUETTE_CLASS_NAME = "com.example.Baguette"
    const val ANSWER_FIELD_NAME = "answer"
    const val TREASURE_CHEST_CLASS_NAME = "com.example.TreasureChest"
    const val CONTENT_FIELD_NAME = "content"
  }
}