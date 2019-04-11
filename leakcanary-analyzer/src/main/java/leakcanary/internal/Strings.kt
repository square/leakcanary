package leakcanary.internal

fun String.lastSegment(segmentingChar: Char): String {
  val separator = lastIndexOf(segmentingChar)
  return if (separator == -1) this else this.substring(separator + 1)
}