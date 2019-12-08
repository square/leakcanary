package shark

/**
 * In memory store that can be used to store objects in a given [HeapGraph] instance.
 * This is a simple [MutableMap] of [String] to [Any], but with unsafe generics access.
 */
class GraphContext {
  private val store = mutableMapOf<String, Any?>()
  operator fun <T> get(key: String): T? {
    @Suppress("UNCHECKED_CAST")
    return store[key] as T?
  }

  /**
   * @see MutableMap.getOrPut
   */
  fun <T> getOrPut(
    key: String,
    defaultValue: () -> T
  ): T {
    @Suppress("UNCHECKED_CAST")
    return store.getOrPut(key, {
      defaultValue()
    }) as T
  }

  /**
   * @see MutableMap.set
   */
  operator fun <T> set(
    key: String,
    value: T
  ) {
    store[key] = (value as Any?)
  }

  /**
   * @see MutableMap.containsKey
   */
  operator fun contains(key: String): Boolean {
    return key in store
  }

  /**
   * @see MutableMap.remove
   */
  operator fun minusAssign(key: String) {
    @Suppress("UNCHECKED_CAST")
    store -= key
  }
}