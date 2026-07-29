package shark

import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import okio.Buffer

/**
 * Reads records in a Hprof source, one at a time with a specific position and size.
 * Call [openReaderFor] to obtain a new instance.
 *
 * [readRecord] can be called concurrently from several threads, as long as [source] supports
 * concurrent reads (see [RandomAccessSource.read]).
 */
class RandomAccessHprofReader private constructor(
  private val source: RandomAccessSource,
  private val hprofHeader: HprofHeader
) : Closeable {

  /**
   * The record readers that aren't currently in use, ready to be borrowed by [readRecord]. A
   * record reader is bound to the buffer it reads from, so a read can't share one with another
   * read in flight: this holds as many readers as the highest number of reads that ever ran
   * concurrently, which is one for a single threaded caller.
   *
   * That high water mark isn't capped, unlike the scratch array pool in FileSourceProvider, because
   * an idle reader holds on to almost nothing: its buffer is cleared before it comes back here,
   * which hands the segments it read into back to okio's pool. So a spike of parallel reads leaves
   * a few hundred bytes per thread behind, and capping this would instead mean allocating a reader
   * per read on the hottest path in Shark.
   */
  private val idleRecordReaders = ConcurrentLinkedQueue<BufferedRecordReader>()

  /**
   * Loads [recordSize] bytes at [recordPosition] into a buffer that backs a [HprofRecordReader]
   * then calls [withRecordReader] with that reader as a receiver. [withRecordReader] is expected
   * to use the receiver reader to read one record of exactly [recordSize] bytes.
   * @return the results from [withRecordReader]
   */
  fun <T> readRecord(
    recordPosition: Long,
    recordSize: Long,
    withRecordReader: HprofRecordReader.() -> T
  ): T {
    require(recordSize > 0L) {
      "recordSize $recordSize must be > 0"
    }
    val recordReader = idleRecordReaders.poll() ?: BufferedRecordReader(hprofHeader)
    try {
      val buffer = recordReader.buffer
      var mutablePos = recordPosition
      var mutableByteCount = recordSize

      while (mutableByteCount > 0L) {
        val bytesRead = source.read(buffer, mutablePos, mutableByteCount)
        check(bytesRead > 0) {
          "Requested $mutableByteCount bytes after reading ${mutablePos - recordPosition}, got 0 bytes instead."
        }
        mutablePos += bytesRead
        mutableByteCount -= bytesRead
      }
      return withRecordReader(recordReader.reader).apply {
        check(buffer.size == 0L) {
          "Buffer not fully consumed: ${buffer.size} bytes left"
        }
      }
    } finally {
      // A read that failed part way through leaves bytes behind, which would then be read as the
      // start of the next record.
      recordReader.buffer.clear()
      idleRecordReaders += recordReader
    }
  }

  override fun close() {
    source.close()
  }

  private class BufferedRecordReader(hprofHeader: HprofHeader) {
    val buffer = Buffer()
    val reader = HprofRecordReader(hprofHeader, buffer)
  }

  companion object {

    fun openReaderFor(
      hprofFile: File,
      hprofHeader: HprofHeader = HprofHeader.parseHeaderOf(hprofFile)
    ): RandomAccessHprofReader {
      val sourceProvider = FileSourceProvider(hprofFile)
      return openReaderFor(sourceProvider, hprofHeader)
    }

    fun openReaderFor(
      hprofSourceProvider: RandomAccessSourceProvider,
      hprofHeader: HprofHeader = hprofSourceProvider.openRandomAccessSource()
        .use { HprofHeader.parseHeaderOf(it.asStreamingSource()) }
    ): RandomAccessHprofReader {
      return RandomAccessHprofReader(hprofSourceProvider.openRandomAccessSource(), hprofHeader)
    }
  }
}
