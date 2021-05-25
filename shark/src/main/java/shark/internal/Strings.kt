package shark.internal

import java.math.BigInteger
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

@JvmField
internal val UTF_8: Charset = Charset.forName("UTF-8")

internal fun String.lastSegment(segmentingChar: Char): String {
  val separator = lastIndexOf(segmentingChar)
  return if (separator == -1) this else this.substring(separator + 1)
}

internal fun String.createSHA1Hash(): String = createHash(this, "SHA-1")

private fun createHash(
  text: String,
  algorithm: String
): String {
  try {
    // Create MD5 Hash.
    val digest = MessageDigest.getInstance(algorithm)
    digest.update(text.getBytes())

    return BigInteger(1, digest.digest()).toString(16).padStart(40, '0')
  } catch (e: NoSuchAlgorithmException) {
    throw AssertionError("Unable to construct MessageDigest for $algorithm")
  }
}

/** Gets the string as an array of UTF-8 bytes. */
internal fun String.getBytes(): ByteArray = toByteArray(UTF_8)