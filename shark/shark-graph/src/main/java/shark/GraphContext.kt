package shark

import java.util.concurrent.ConcurrentHashMap

/**
 * In memory store that can be used to store objects in a given [HeapGraph] instance.
 * This is a simple [MutableMap] of [String] to [Any], but with unsafe generics access.
 *
 * Thread safe: several threads reading from the same [HeapGraph] can share its context.
 */
class GraphContext {
  private val store = ConcurrentHashMap<String, Any>()

  operator fun <T> get(key: String): T? {
    return store[key].unwrapNull()
  }

  /**
   * @see MutableMap.getOrPut
   *
   * Unlike [MutableMap.getOrPut], [defaultValue] is called without holding any lock, so when
   * several threads race to compute the value for the same key [defaultValue] may be called more
   * than once. The value that made it into the store first then wins and is returned to all
   * callers.
   */
  fun <T> getOrPut(
    key: String,
    defaultValue: () -> T
  ): T {
    val existingValue = store[key]
    if (existingValue != null) {
      return existingValue.unwrapNull()
    }
    val newValue = defaultValue()
    val valueThatWon = store.putIfAbsent(key, newValue.wrapNull())
    return if (valueThatWon != null) valueThatWon.unwrapNull() else newValue
  }

  /**
   * @see MutableMap.set
   */
  operator fun <T> set(
    key: String,
    value: T
  ) {
    store[key] = value.wrapNull()
  }

  /**
   * @see MutableMap.containsKey
   */
  operator fun contains(key: String): Boolean {
    return store.containsKey(key)
  }

  /**
   * @see MutableMap.remove
   */
  operator fun minusAssign(key: String) {
    store -= key
  }

  /**
   * [ConcurrentHashMap] doesn't support null values, but this store does: a key set to null is a
   * key that's present with a null value, which matters for [contains] as well as for [getOrPut],
   * which then remembers that the value is null instead of computing it again.
   */
  private object NullValue

  private fun Any?.wrapNull(): Any = this ?: NullValue

  @Suppress("UNCHECKED_CAST")
  private fun <T> Any?.unwrapNull(): T = (if (this === NullValue) null else this) as T
}
