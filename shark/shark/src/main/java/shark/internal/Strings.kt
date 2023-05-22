package shark.internal

internal fun String.lastSegment(segmentingChar: Char): String {
  val separator = lastIndexOf(segmentingChar)
  return if (separator == -1) this else this.substring(separator + 1)
}

internal fun String.createSHA1Hash() = ByteStringCompat.encodeUtf8(this).sha1().hex()
