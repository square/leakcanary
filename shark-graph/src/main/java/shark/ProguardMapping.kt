package shark

class ProguardMapping {

  // Classes can be simply stored as a obfuscatedName -> clearName
  // For fields it's a bit more complicated since we need to know
  // the class that a given field belongs to (two different classes
  // can have a field with the same name). So files are stored as:
  // obfuscatedClassName.obfuscatedFieldName -> clearFieldName
  private val obfuscatedToClearNamesMap = linkedMapOf<String, String>()

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

  /**
   * Adds entry to the obfuscatedToClearNamesMap map.
   */
  fun addMapping(obfuscatedName: String, clearName: String) {
    obfuscatedToClearNamesMap[obfuscatedName] = clearName
  }
}
