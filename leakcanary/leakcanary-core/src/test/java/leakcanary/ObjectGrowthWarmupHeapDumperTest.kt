package leakcanary

import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeader
import shark.dumpToBytes

class ObjectGrowthWarmupHeapDumperTest {

  @Test fun `heap dump 1 as hex constant matches generated heap dump hex`() {
    assertThat(ObjectGrowthWarmupHeapDumper.heapDump1Hex()).isEqualTo(dumpGrowingListHeapAsHex(1))
  }

  @Test fun `heap dump 2 as hex constant matches generated heap dump hex`() {
    assertThat(ObjectGrowthWarmupHeapDumper.heapDump2Hex()).isEqualTo(dumpGrowingListHeapAsHex(2))
  }

  @Test fun `heap dump 3 as hex constant matches generated heap dump hex`() {
    assertThat(ObjectGrowthWarmupHeapDumper.heapDump3Hex()).isEqualTo(dumpGrowingListHeapAsHex(3))
  }

  private fun dumpGrowingListHeapAsHex(listItemCount: Int): String {
    val heapDumpTimestamp = ("0b501e7e" + "ca55e77e").decodeHex().toByteArray().toLong()
    return dumpToBytes(hprofHeader = HprofHeader(heapDumpTimestamp = heapDumpTimestamp)) {
      "Holder" clazz {
        val refs = (1..listItemCount).map {
          instance(objectClassId)
        }.toTypedArray()
        staticField["list"] = objectArray(*refs)
      }
    }.toByteString().hex()
  }

  private fun ByteArray.toLong(): Long {
    check(size == 8)
    var pos = 0
    return (this[pos++].toLong() and 0xffL shl 56
      or (this[pos++].toLong() and 0xffL shl 48)
      or (this[pos++].toLong() and 0xffL shl 40)
      or (this[pos++].toLong() and 0xffL shl 32)
      or (this[pos++].toLong() and 0xffL shl 24)
      or (this[pos++].toLong() and 0xffL shl 16)
      or (this[pos++].toLong() and 0xffL shl 8)
      or (this[pos].toLong() and 0xffL))
  }
}
