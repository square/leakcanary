package shark.internal

internal fun String.lastSegment(segmentingChar: Char): String {
  val separator = lastIndexOf(segmentingChar)
  return if (separator == -1) this else this.substring(separator + 1)
}

internal fun String.createSHA1Hash() = ByteStringCompat.encodeUtf8(this).sha1().hex()

/**
 * [objectId] in hex, the way shark writes an object id for someone to read: `0x12c0a6b8`. The
 * same as the copy in `shark-graph`, which says why. This module can't see that one.
 */
internal fun hexObjectId(objectId: Long): String {
  val address = if (objectId < 0L) objectId and LOW_32_BITS else objectId
  return "0x${address.toString(16)}"
}

private const val LOW_32_BITS = 0xffffffffL
