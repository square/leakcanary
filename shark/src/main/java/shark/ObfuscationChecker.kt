package shark

interface ObfuscationChecker {
  fun isCodeObfuscated(): Boolean
}

class ReflectionObfuscationChecker : ObfuscationChecker {

  /**
   * This function uses reflection to find field by name in a given class.
   * If code is obfuscated then this lookup will fail.
   */
  override fun isCodeObfuscated(): Boolean {
    return try {
      Class.forName("shark.LeakTrace")
          .getDeclaredField("gcRootType")
      false
    } catch (e: Exception) {
      true
    }
  }
}
