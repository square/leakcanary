package shark.internal

import okio.ByteString.Companion.encodeUtf8

internal fun String.lastSegment(segmentingChar: Char): String {
  val separator = lastIndexOf(segmentingChar)
  return if (separator == -1) this else this.substring(separator + 1)
}

internal fun String.createSHA1Hash(): String = encodeUtf8().sha1().hex()