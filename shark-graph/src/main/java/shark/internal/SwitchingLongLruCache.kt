package shark.internal

class SwitchingLongLruCache<V>(maxSize: Int) {

  private val longCache = LongLruCache<V>(maxSize)
  private val linkedListCache = LruCache<Long, V>(maxSize)

  operator fun get(key: Long): V? {
    return if (useLinkedListLruCache) {
      linkedListCache[key]
    } else {
      longCache[key]
    }
  }

  fun put(
    key: Long,
    value: V
  ): V? {
    return if (useLinkedListLruCache) {
      linkedListCache.put(key, value)
    } else {
      longCache.put(key, value)
    }
  }

  companion object {
    @Volatile
    var useLinkedListLruCache = true
  }
}