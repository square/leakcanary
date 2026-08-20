package shark

import okio.BufferedSource
import okio.GzipSource
import okio.Source
import okio.buffer

/**
 * Can open [Source] instances.
 */
fun interface StreamingSourceProvider {
  fun openStreamingSource(): BufferedSource
}

/**
 * Returns a [StreamingSourceProvider] that hands out the content of the sources this one opens,
 * decompressed when that content is gzipped and as is when it isn't. Which one it is comes from the
 * content itself rather than from a file name, so a heap dump that was gzipped after it was written
 * reads the same as one that never was.
 */
fun StreamingSourceProvider.gunzipIfGzipped(): StreamingSourceProvider = StreamingSourceProvider {
  val source = openStreamingSource()
  if (source.startsWithGzipMagicNumber()) {
    GzipSource(source).buffer()
  } else {
    source
  }
}

private fun BufferedSource.startsWithGzipMagicNumber(): Boolean {
  return request(GZIP_MAGIC_NUMBER.size.toLong()) &&
    GZIP_MAGIC_NUMBER.withIndex().all { (index, byte) -> buffer[index.toLong()] == byte }
}

/** The two bytes every gzip stream starts with. */
private val GZIP_MAGIC_NUMBER = byteArrayOf(0x1f, 0x8b.toByte())
