package shark.internal

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

/**
 * Derived from
 * [this snippet](http://www.androidsnippets.com/create-a-md5-hash-and-dump-as-a-hex-string).
 */
private fun createHash(
  text: String,
  algorithm: String
): String {
  try {
    // Create MD5 Hash.
    val digest = MessageDigest.getInstance(algorithm)
    digest.update(text.getBytes())
    val messageDigest = digest.digest()

    // Create Hex String.
    val hexString = StringBuilder()
    for (b in messageDigest) {
      hexString.append(Integer.toHexString(0xff and b.toInt()))
    }
    return hexString.toString()
  } catch (e: NoSuchAlgorithmException) {
    throw AssertionError("Unable to construct MessageDigest for $algorithm")
  }
}

/** Gets the string as an array of UTF-8 bytes. */
internal fun String.getBytes(): ByteArray = toByteArray(UTF_8)