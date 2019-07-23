package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StringRecord
import shark.ValueHolder.ReferenceHolder
import java.io.File

class HprofWriterTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  private var lastId = 0L
  private val id: Long
    get() = ++lastId

  @Test
  fun writeAndReadHprof() {
    val hprofFile = testFolder.newFile("temp.hprof")
    val records = createRecords()

    hprofFile.writeRecords(records)

    hprofFile.readHprof { graph ->
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

  private fun File.writeRecords(
    records: List<HprofRecord>
  ) {
    HprofWriter.open(this)
        .use { writer ->
          records.forEach { record ->
            writer.write(record)
          }
        }
  }

  fun File.readHprof(block: (HeapGraph) -> Unit) {
    Hprof.open(this)
        .use { hprof ->
          block(HprofHeapGraph.indexHprof(hprof))
        }
  }

  companion object {
    const val MAGIC_WAND_CLASS_NAME = "com.example.MagicWand"
    const val BAGUETTE_CLASS_NAME = "com.example.Baguette"
    const val ANSWER_FIELD_NAME = "answer"
    const val TREASURE_CHEST_CLASS_NAME = "com.example.TreasureChest"
    const val CONTENT_FIELD_NAME = "content"
  }
}