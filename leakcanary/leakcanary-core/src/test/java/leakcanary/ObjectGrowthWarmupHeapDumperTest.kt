package leakcanary

import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeader
import shark.HprofWriterHelper
import shark.ValueHolder
import shark.dumpToBytes

class ObjectGrowthWarmupHeapDumperTest {

  @Test fun `heap dump 1 as hex constant matches generated heap dump hex`() {
    assertThat(ObjectGrowthWarmupHeapDumper.heapDump1Hex(androidHeap = false)).isEqualTo(dumpGrowingListHeapAsHex(1))
  }

  @Test fun `heap dump 2 as hex constant matches generated heap dump hex`() {
    assertThat(ObjectGrowthWarmupHeapDumper.heapDump2Hex(androidHeap = false)).isEqualTo(dumpGrowingListHeapAsHex(2))
  }

  @Test fun `heap dump 3 as hex constant matches generated heap dump hex`() {
    assertThat(ObjectGrowthWarmupHeapDumper.heapDump3Hex(androidHeap = false)).isEqualTo(dumpGrowingListHeapAsHex(3))
  }

  @Test fun `android heap dump 1 as hex constant matches generated heap dump hex`() {
    assertThat(ObjectGrowthWarmupHeapDumper.heapDump1Hex(androidHeap = true)).isEqualTo(dumpAndroidGrowingListHeapAsHex(1))
  }

  @Test fun `android heap dump 2 as hex constant matches generated heap dump hex`() {
    assertThat(ObjectGrowthWarmupHeapDumper.heapDump2Hex(androidHeap = true)).isEqualTo(dumpAndroidGrowingListHeapAsHex(2))
  }

  @Test fun `android heap dump 3 as hex constant matches generated heap dump hex`() {
    assertThat(ObjectGrowthWarmupHeapDumper.heapDump3Hex(androidHeap = true)).isEqualTo(dumpAndroidGrowingListHeapAsHex(3))
  }

  private fun dumpGrowingListHeapAsHex(listItemCount: Int): String {
    val heapDumpTimestamp = ("0b501e7e" + "ca55e77e").decodeHex().toByteArray().toLong()
    return dumpToBytes(hprofHeader = HprofHeader(heapDumpTimestamp = heapDumpTimestamp)) {
      growingList(listItemCount)
    }.toByteString().hex()
  }

  private fun dumpAndroidGrowingListHeapAsHex(listItemCount: Int): String {
    val heapDumpTimestamp = ("0b501e7e" + "ca55e77e").decodeHex().toByteArray().toLong()
    return dumpToBytes(hprofHeader = HprofHeader(heapDumpTimestamp = heapDumpTimestamp)) {
      "android.os.Build" clazz {
        staticField["MANUFACTURER"] = string("M")
        staticField["ID"] = string("I")
      }
      "android.os.Build\$VERSION" clazz {
        staticField["SDK_INT"] = ValueHolder.IntHolder(42)
      }
      growingList(listItemCount)
    }.toByteString().hex()
  }


  private fun HprofWriterHelper.growingList(listItemCount: Int) {
    "Holder" clazz {
      val refs = (1..listItemCount).map {
        instance(objectClassId)
      }.toTypedArray()
      staticField["list"] = objectArray(*refs)
    }
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
