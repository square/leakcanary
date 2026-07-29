package shark

import java.util.concurrent.ConcurrentHashMap

/**
 * In memory store that can be used to store objects in a given [HeapGraph] instance.
 * This is a simple [MutableMap] of [String] to [Any], but with unsafe generics access.
 *
 * A null value and a missing key are the same thing here: [set] with a null value removes the key,
 * and a [getOrPut] that computes null doesn't store anything.
 *
 * Thread safe: several threads reading from the same [HeapGraph] can share its context. [getOrPut]
 * and [compute] both read a value and then write one, and differ in how they handle threads racing
 * for the same key, see their documentation.
 */
class GraphContext {
  private val store = ConcurrentHashMap<String, Any>()

  operator fun <T> get(key: String): T? {
    @Suppress("UNCHECKED_CAST")
    return store[key] as T?
  }

  /**
   * @see MutableMap.getOrPut
   *
   * Unlike [MutableMap.getOrPut], [defaultValue] is called without holding any lock, so when
   * several threads race to compute the value for the same key [defaultValue] may be called more
   * than once. The value that made it into the store first then wins and is returned to all
   * callers. [compute] is the one to use when the new value depends on the current one, or when
   * computing it twice isn't acceptable.
   */
  fun <T> getOrPut(
    key: String,
    defaultValue: () -> T
  ): T {
    val existingValue = get<T>(key)
    if (existingValue != null) {
      return existingValue
    }
    val newValue = defaultValue()
    if (newValue == null) {
      return newValue
    }
    @Suppress("UNCHECKED_CAST")
    return (store.putIfAbsent(key, newValue) ?: newValue) as T
  }

  /**
   * Atomically replaces the value for [key] with the result of [remapping], which is called with
   * the current value or null if there is none, and returns that result. This is how to read a
   * value and then update it, e.g. incrementing a counter that several threads share:
   *
   * ```
   * val readCount = context.compute<Int>("readCount") { previousCount -> (previousCount ?: 0) + 1 }
   * ```
   *
   * Returning null from [remapping] removes the key.
   *
   * [remapping] is called while holding a lock on [key], unlike [getOrPut], so it is called exactly
   * once but it should be quick and it should not read from or write to this context again.
   */
  fun <T> compute(
    key: String,
    remapping: (T?) -> T?
  ): T? {
    @Suppress("UNCHECKED_CAST")
    return store.compute(key) { _, currentValue ->
      remapping(currentValue as T?)
    } as T?
  }

  /**
   * @see MutableMap.set
   *
   * Setting a null [value] removes [key], as a null value and a missing key are the same thing
   * here.
   */
  operator fun <T> set(
    key: String,
    value: T
  ) {
    if (value == null) {
      store -= key
    } else {
      store[key] = value
    }
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
}
