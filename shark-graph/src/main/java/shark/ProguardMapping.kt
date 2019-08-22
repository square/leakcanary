package shark

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.text.ParseException


class ProguardMapping private constructor(
        proguardMappingFile: InputStream
) {

    // Classes can be simply stored as a obfuscatedName -> clearName
    // For fields it's a bit more complicated since we need to know
    // the class that a given field belongs to (two different classes
    // can have a field with the same name). So files are stored as:
    // obfuscatedClassName.obfuscatedFieldName -> clearFieldName
    private val obfuscatedToClearNamesMap = hashMapOf<String, String>()

    init {
        parseMappingFile(proguardMappingFile)
    }

    /**
     * Returns deobfuscated class name or original string if there is no
     * mapping for given obfuscated name.
     */
    fun deobfuscateClassName(obfuscatedClassName: String): String {
        return obfuscatedToClearNamesMap[obfuscatedClassName] ?: obfuscatedClassName
    }

    /**
     * Returns deobfuscated field name or original string if there is no
     * mapping for given obfuscated name.
     */
    fun deobfuscateFieldName(obfuscatedClass: String, obfuscatedField: String): String {
        return obfuscatedToClearNamesMap["$obfuscatedClass.$obfuscatedField"] ?: return obfuscatedField
    }

    @Throws(FileNotFoundException::class, IOException::class, ParseException::class)
    private fun parseMappingFile(mappingInputStream: InputStream) {
        mappingInputStream.bufferedReader(Charsets.UTF_8).use { bufferedReader ->

            var currentClassName: String? = null
            while (true) {
                val line = bufferedReader.readLine()?.trim() ?: break

                if (line.isEmpty() || line.startsWith(HASH_SYMBOL)) {
                    // empty line or comment
                    continue
                }

                if (line.endsWith(COLON_SYMBOL)) {
                    currentClassName = parseClassMapping(line)
                } else if (currentClassName != null) {
                    if (!line.contains(OPENING_PAREN_SYMBOL)) {
                        // if the line contains "(" then it's a method mapping, we care only about fields
                        parseClassField(line, currentClassName)
                    }
                }
            }
        }
    }

    // classes are stored as "clearName -> obfuscatedName:"
    private fun parseClassMapping(line: String): String? {
        val arrowPosition = line.indexOf(ARROW_SYMBOL)
        if (arrowPosition == -1) {
            return null
        }

        val colonPosition = line.indexOf(COLON_SYMBOL, arrowPosition + ARROW_SYMBOL.length)
        if (colonPosition == -1) {
            return null
        }

        val clearClassName = line.substring(0, arrowPosition).trim()
        val obfuscatedClassName = line.substring(arrowPosition + ARROW_SYMBOL.length, colonPosition).trim()

        obfuscatedToClearNamesMap[obfuscatedClassName] = clearClassName

        return obfuscatedClassName
    }

    // fields are stored as "typeName clearFieldName -> obfuscatedFieldName"
    private fun parseClassField(line: String, currentClassName: String) {
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

        obfuscatedToClearNamesMap["$currentClassName.$obfuscatedFieldName"] = clearFieldName
    }

    companion object {
        private const val HASH_SYMBOL = "#"
        private const val ARROW_SYMBOL = "->"
        private const val COLON_SYMBOL = ":"
        private const val SPACE_SYMBOL = " "
        private const val OPENING_PAREN_SYMBOL = "("
        private const val ARRAY_SYMBOL = "[]"

        fun createProguardMapping(mapping: InputStream): ProguardMapping {
            return ProguardMapping(mapping)
        }

        fun createProguardMapping(mapping: File): ProguardMapping {
            return ProguardMapping(mapping.inputStream())
        }
    }
}
