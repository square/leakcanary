package shark

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.text.ParseException

class ProguardMappingReader(
  private val proguardMappingInputStream: InputStream
) {

  @Throws(FileNotFoundException::class, IOException::class, ParseException::class)
  fun readProguardMapping(): ProguardMapping {
    val proguardMapping = ProguardMapping()
    proguardMappingInputStream.bufferedReader(Charsets.UTF_8).use { bufferedReader ->

      var currentClassName: String? = null
      while (true) {
        val line = bufferedReader.readLine()?.trim() ?: break

        if (line.isEmpty() || line.startsWith(HASH_SYMBOL)) {
          // empty line or comment
          continue
        }

        if (line.endsWith(COLON_SYMBOL)) {
          currentClassName = parseClassMapping(line, proguardMapping)
        } else if (currentClassName != null) {
          val isMethodMapping = line.contains(OPENING_PAREN_SYMBOL)
          if (!isMethodMapping) {
            parseClassField(line, currentClassName, proguardMapping)
          }
        }
      }
    }
    return proguardMapping
  }

  // classes are stored as "clearName -> obfuscatedName:"
  private fun parseClassMapping(line: String, proguardMapping: ProguardMapping): String? {
    val arrowPosition = line.indexOf(ARROW_SYMBOL)
    if (arrowPosition == -1) {
      return null
    }

    val colonPosition = line.indexOf(COLON_SYMBOL, arrowPosition + ARROW_SYMBOL.length)
    if (colonPosition == -1) {
      return null
    }

    val clearClassName = line.substring(0, arrowPosition).trim()
    val obfuscatedClassName =
        line.substring(arrowPosition + ARROW_SYMBOL.length, colonPosition).trim()

    proguardMapping.addMapping(obfuscatedClassName, clearClassName)

    return obfuscatedClassName
  }

  // fields are stored as "typeName clearFieldName -> obfuscatedFieldName"
  private fun parseClassField(
    line: String,
    currentClassName: String,
    proguardMapping: ProguardMapping
  ) {
    val spacePosition = line.indexOf(SPACE_SYMBOL)
    if (spacePosition == -1) {
      return
    }

    val arrowPosition = line.indexOf(ARROW_SYMBOL, spacePosition + SPACE_SYMBOL.length)
    if (arrowPosition == -1) {
      return
    }

    val clearFieldName = line.substring(spacePosition + SPACE_SYMBOL.length, arrowPosition).trim()
    val obfuscatedFieldName = line.substring(arrowPosition + ARROW_SYMBOL.length).trim()

    proguardMapping.addMapping("$currentClassName.$obfuscatedFieldName", clearFieldName)
  }

  companion object {
    private const val HASH_SYMBOL = "#"
    private const val ARROW_SYMBOL = "->"
    private const val COLON_SYMBOL = ":"
    private const val SPACE_SYMBOL = " "
    private const val OPENING_PAREN_SYMBOL = "("
  }
}