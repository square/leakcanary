package shark.internal

import java.util.LinkedHashMap
import kotlin.collections.MutableMap.MutableEntry

/**
 * API is a simplified version of android.util.LruCache
 * Implementation is inspired from http://chriswu.me/blog/a-lru-cache-in-10-lines-of-java/
 */
internal class LruCache<K, V>(
  val maxSize: Int
) {
  private val cache: LinkedHashMap<K, V>

  val size
    get() = cache.size

  var putCount: Int = 0
    private set
  var evictionCount: Int = 0
    private set
  var hitCount: Int = 0
    private set
  var missCount: Int = 0
    private set

  init {
    require(maxSize > 0) {
      "maxSize=$maxSize <= 0"
    }
    this.cache = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableEntry<K, V>?) = if (size > maxSize) {
        evictionCount++
        true
      } else {
        false
      }
    }
  }

  operator fun get(key: K?): V? {
    // get() moves the key to the front
    val value: V? = cache[key]
    return if (value != null) {
      hitCount++
      value
    } else {
      missCount++
      null
    }
  }

  fun put(
    key: K,
    value: V
  ): V? {
    putCount++
    return cache.put(key, value)
  }

  fun remove(key: K): V? {
    return cache.remove(key)
  }

  fun evictAll() {
    cache.clear()
  }

  override fun toString(): String {
    val accesses = hitCount + missCount
    val hitPercent = if (accesses != 0) 100 * hitCount / accesses else 0
    return String.format(
        "LruCache[maxSize=%d,hits=%d,misses=%d,hitRate=%d%%]",
        maxSize, hitCount, missCount, hitPercent
    )
  }
}