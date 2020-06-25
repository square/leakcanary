package shark

import org.assertj.core.api.Assertions
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

class HprofDeobfuscatorTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  private var lastId = 0L
  private val id: Long
    get() = ++lastId

  @Test
  fun deobfuscateHprof() {
    val hprofFile = testFolder.newFile("temp.hprof")
    hprofFile.writeRecords(createRecords())

    val proguardMappingText = """
            $WALLET_CLASS_NAME -> $OBFUSCATED_WALLET_CLASS_NAME:
                type $MONEY_STATIC_FIELD_NAME -> $OBFUSCATED_MONEY_STATIC_FIELD_NAME
            $MONEY_CLASS_NAME -> $OBFUSCATED_MONEY_CLASS_NAME:
                type $PENNY_INSTANCE_FIELD_NAME -> $OBFUSCATED_PENNY_INSTANCE_FIELD_NAME
        """.trimIndent()

    val proguardFile = testFolder.newFile("mapping.txt")
      .apply {
        writeText(proguardMappingText, Charsets.UTF_8)
      }

    val deobfuscator = HprofDeobfuscator()
    val deobfuscatedFile = deobfuscator.deobfuscate(proguardFile, hprofFile)

    deobfuscatedFile.readHprof { graph ->
      val walletClass = graph.findClassByName(WALLET_CLASS_NAME)!!
      val moneyInstance = walletClass[MONEY_STATIC_FIELD_NAME]!!.value.asObject!!.asInstance!!

      Assertions.assertThat(moneyInstance.instanceClass.name).isEqualTo(MONEY_CLASS_NAME)

      Assertions.assertThat(
        moneyInstance.readFields()
          .map { it.name }
          .toList()
      ).contains(PENNY_INSTANCE_FIELD_NAME)

      Assertions.assertThat(
        walletClass.readStaticFields()
          .map { it.name }
          .toList()
      ).contains(MONEY_STATIC_FIELD_NAME)
    }
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

  private fun File.readHprof(block: (HeapGraph) -> Unit) {
    Hprof.open(this)
      .use { hprof ->
        block(HprofHeapGraph.indexHprof(hprof))
      }
  }

  private fun createRecords(): List<HprofRecord> {
    val obfuscatedWalletClassName = StringRecord(id, OBFUSCATED_WALLET_CLASS_NAME)
    val obfuscatedMoneyClassName = StringRecord(id, OBFUSCATED_MONEY_CLASS_NAME)
    val obfuscatedPennyInstanceFieldName = StringRecord(id, OBFUSCATED_PENNY_INSTANCE_FIELD_NAME)
    val obfuscatedMoneyStaticFieldName = StringRecord(id, OBFUSCATED_MONEY_STATIC_FIELD_NAME)

    val loadObfuscatedMoneyClass = LoadClassRecord(1, id, 1, obfuscatedMoneyClassName.id)
    val loadObfuscatedWalletClass = LoadClassRecord(1, id, 1, obfuscatedWalletClassName.id)

    val obfuscatedMoneyClassDump = ClassDumpRecord(
      id = loadObfuscatedMoneyClass.id,
      stackTraceSerialNumber = 1,
      superclassId = 0,
      classLoaderId = 0,
      signersId = 0,
      protectionDomainId = 0,
      instanceSize = 0,
      staticFields = listOf(),
      fields = listOf(
        FieldRecord(obfuscatedPennyInstanceFieldName.id, PrimitiveType.INT.hprofType)
      )
    )

    val obfuscatedMoneyInstanceDump = InstanceDumpRecord(
      id = id,
      stackTraceSerialNumber = 1,
      classId = loadObfuscatedMoneyClass.id,
      fieldValues = byteArrayOf(0x0, 0x1, 0x2, 0x3)
    )

    val obfuscatedWalletClassDump = ClassDumpRecord(
      id = loadObfuscatedWalletClass.id,
      stackTraceSerialNumber = 1,
      superclassId = 0,
      classLoaderId = 0,
      signersId = 0,
      protectionDomainId = 0,
      instanceSize = 0,
      staticFields = listOf(
        StaticFieldRecord(
          obfuscatedMoneyStaticFieldName.id, PrimitiveType.REFERENCE_HPROF_TYPE,
          ReferenceHolder(obfuscatedMoneyInstanceDump.id)
        )
      ),
      fields = emptyList()
    )

    return listOf(
      obfuscatedWalletClassName, obfuscatedMoneyClassName, obfuscatedPennyInstanceFieldName,
      obfuscatedMoneyStaticFieldName, loadObfuscatedMoneyClass, loadObfuscatedWalletClass,
      obfuscatedMoneyClassDump, obfuscatedMoneyInstanceDump, obfuscatedWalletClassDump
    )
  }

  companion object {
    const val WALLET_CLASS_NAME = "Wallet"
    const val MONEY_CLASS_NAME = "Money"
    const val MONEY_STATIC_FIELD_NAME = "money"
    const val PENNY_INSTANCE_FIELD_NAME = "penny"

    const val OBFUSCATED_WALLET_CLASS_NAME = "a"
    const val OBFUSCATED_MONEY_CLASS_NAME = "b"
    const val OBFUSCATED_MONEY_STATIC_FIELD_NAME = "c.d"
    const val OBFUSCATED_PENNY_INSTANCE_FIELD_NAME = "e.f"
  }
}