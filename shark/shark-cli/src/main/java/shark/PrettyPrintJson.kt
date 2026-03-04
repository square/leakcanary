package shark

class PrettyPrintJson {

  fun format(json: String): String {
    val out = StringBuilder()
    var depth = 0
    var inString = false
    var needsNewlineAndIndent = false
    var i = 0
    while (i < json.length) {
      val c = json[i]
      when {
        inString -> {
          out.append(c)
          when (c) {
            // consume escaped char verbatim
            '\\' -> out.append(json[++i])
            '"' -> inString = false
          }
        }
        // discard existing formatting
        c.isWhitespace() -> Unit
        c == '}' || c == ']' -> {
          needsNewlineAndIndent = false
          out.newlineAndIndent(--depth)
          out.append(c)
        }
        else -> {
          if (needsNewlineAndIndent) {
            out.newlineAndIndent(depth)
            needsNewlineAndIndent = false
          }
          out.append(c)
          when (c) {
            '"' -> inString = true
            '{', '[' -> { depth++; needsNewlineAndIndent = true }
            ',' -> needsNewlineAndIndent = true
            ':' -> out.append(' ')
          }
        }
      }
      i++
    }
    return out.toString()
  }

  private fun StringBuilder.newlineAndIndent(depth: Int) {
    append('\n')
    repeat(depth) { append("  ") }
  }
}
