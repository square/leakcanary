package shark.internal

import okio.ByteString.Companion.encodeUtf8
import java.nio.charset.Charset

@JvmField
internal val UTF_8: Charset = Charset.forName("UTF-8")

internal fun String.lastSegment(segmentingChar: Char): String {
  val separator = lastIndexOf(segmentingChar)
  return if (separator == -1) this else this.substring(separator + 1)
}

internal fun String.createSHA1Hash(): String = encodeUtf8().sha1().hex()

/** Gets the string as an array of UTF-8 bytes. */
internal fun String.getBytes(): ByteArray = toByteArray(UTF_8)