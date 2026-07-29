package shark

import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.PrimitiveType.LONG

/**
 * An array with more than 2 GB worth of elements can't be covered by a heap dump built with the
 * `dump {}` DSL: writing one means holding its elements in memory first, which is 2 GB of heap for
 * the fixture alone, on top of a 2 GB file. So these read the record fields the skips depend on
 * from a source that hands out the elements as an endless run of zeros, which costs nothing.
 */
class HprofRecordReaderLargeArrayTest {

  private val identifierByteSize = 8

  private val header = HprofHeader(identifierByteSize = identifierByteSize)

  /** id, stack trace serial number, array length, array class id. */
  private val objectArrayFieldsByteSize = identifierByteSize + 4 + 4 + identifierByteSize

  /** id, stack trace serial number, array length, element type. */
  private val primitiveArrayFieldsByteSize = identifierByteSize + 4 + 4 + 1

  @Test
  fun skips_object_array_holding_more_than_2_gb_of_element_ids() {
    // One element past what arrayLength * identifierByteSize holds in an Int.
    val arrayLength = Int.MAX_VALUE / identifierByteSize + 1
    val reader = readerFor(objectArrayDumpRecordFields(arrayLength))

    reader.skipObjectArrayDumpRecord()

    assertThat(reader.bytesRead)
      .isEqualTo(objectArrayFieldsByteSize + arrayLength.toLong() * identifierByteSize)
  }

  @Test
  fun skips_long_array_holding_more_than_2_gb_of_elements() {
    val arrayLength = Int.MAX_VALUE / LONG.byteSize + 1
    val reader = readerFor(primitiveArrayDumpRecordFields(arrayLength, LONG))

    reader.skipPrimitiveArrayDumpRecord()

    assertThat(reader.bytesRead)
      .isEqualTo(primitiveArrayFieldsByteSize + arrayLength.toLong() * LONG.byteSize)
  }

  private fun objectArrayDumpRecordFields(arrayLength: Int) = Buffer().apply {
    writeLong(OBJECT_ID)
    writeInt(STACK_TRACE_SERIAL_NUMBER)
    writeInt(arrayLength)
    writeLong(ARRAY_CLASS_ID)
  }

  private fun primitiveArrayDumpRecordFields(
    arrayLength: Int,
    type: PrimitiveType
  ) = Buffer().apply {
    writeLong(OBJECT_ID)
    writeInt(STACK_TRACE_SERIAL_NUMBER)
    writeInt(arrayLength)
    writeByte(type.hprofType)
  }

  private fun readerFor(recordFields: Buffer) =
    HprofRecordReader(header, ZeroPaddedSource(recordFields).buffer())

  /**
   * Reads [head] then an endless run of zeros. Every chunk of zeros shares one backing array, so
   * reading past gigabytes of them neither allocates nor copies.
   */
  private class ZeroPaddedSource(private val head: Buffer) : Source {

    private val zeros = Buffer().apply { write(ByteArray(ZEROS_CHUNK_BYTE_SIZE)) }

    override fun read(
      sink: Buffer,
      byteCount: Long
    ): Long {
      if (head.size > 0) {
        return head.read(sink, byteCount)
      }
      val zerosByteCount = minOf(byteCount, zeros.size)
      zeros.copyTo(sink, 0, zerosByteCount)
      return zerosByteCount
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() = Unit

    companion object {
      private const val ZEROS_CHUNK_BYTE_SIZE = 8192
    }
  }

  companion object {
    private const val OBJECT_ID = 42L
    private const val ARRAY_CLASS_ID = 43L
    private const val STACK_TRACE_SERIAL_NUMBER = 1
  }
}
