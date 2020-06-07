/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package shark.internal

import java.util.Arrays
import java.util.Locale

/*
The API of LongLruCache is a simplified version of android.util.LruCache.

The implementation of the LruCache part
is inspired from http://chriswu.me/blog/a-lru-cache-in-10-lines-of-java.

Instead of subclassing LinkedHashMap this class is the result of:
- Inlining LinkedHashMap, HashMap & AbstractMap using sources from https://android.googlesource.com
/platform/libcore/+/jb-mr2-release/luni/src/main/java/java/util/ (AOSP Jelly Bean MR2,
Apache 2 license)
- Replacing the generic parameter K with a primitive long.
- Inlining the LinkedHashMap subclass with the already inlined classes
- Removing unused or redundant code.
- Converting to Kotlin.
 */
internal class LongLruCache<V>(maxSize: Int) {
  private val header: LinkedEntry<V>

  /**
   * The hash table. If this hash map contains a mapping for 0, it is
   * not represented in this hash table.
   */
  private lateinit var table: Array<LinkedEntry<V>?>

  /**
   * The entry representing the 0 key, or null if there's no such mapping.
   */
  private var entryFor0Key: LinkedEntry<V>? = null

  /**
   * The number of mappings in this hash map.
   */
  var size = 0
    private set

  /**
   * The table is rehashed when its size exceeds this threshold.
   * The value of this field is .75 * capacity.
   */
  private var threshold = 0
  private val maxSize: Int

  var evictionCount = 0
    private set
  var putCount = 0
    private set
  var hitCount = 0
    private set
  var missCount = 0
    private set

  fun put(
    key: Long,
    value: V
  ): V? {
    putCount++
    if (key == 0L) {
      return putValueFor0Key(value)
    }
    val hash = longHashCode(key)
    var tab = table
    var index = hash and tab.size - 1
    var e = tab[index]
    while (e != null) {
      if (key == e.key) {
        makeTail(e)
        val oldValue = e.value
        e.value = value
        return oldValue
      }
      e = e.next
    }
    // No entry for (non-null) key is present; create one
    if (size++ > threshold) {
      tab = doubleCapacity()
      index = hash and tab.size - 1
    }
    addNewEntry(key, value, hash, index)
    return null
  }

  private fun putValueFor0Key(value: V): V? {
    val entry = entryFor0Key
    return if (entry == null) {
      addNewEntryFor0Key(value)
      size++
      null
    } else {
      makeTail(entry)
      val oldValue = entry.value
      entry.value = value
      oldValue
    }
  }

  /**
   * Allocate a table of the given capacity and set the threshold accordingly.
   *
   * @param newCapacity must be a power of two
   */
  private fun makeTable(newCapacity: Int): Array<LinkedEntry<V>?> {
    val newTable =
      arrayOfNulls<LinkedEntry<*>?>(newCapacity) as Array<LinkedEntry<V>?>
    table = newTable
    threshold = (newCapacity shr 1) + (newCapacity shr 2) // 3/4 capacity
    return newTable
  }

  /**
   * Doubles the capacity of the hash table. Existing entries are placed in
   * the correct bucket on the enlarged table. If the current capacity is,
   * MAXIMUM_CAPACITY, this method is a no-op. Returns the table, which
   * will be new unless we were already at MAXIMUM_CAPACITY.
   */
  private fun doubleCapacity(): Array<LinkedEntry<V>?> {
    val oldTable = table
    val oldCapacity = oldTable.size
    if (oldCapacity == MAXIMUM_CAPACITY) {
      return oldTable
    }
    val newCapacity = oldCapacity * 2
    val newTable = makeTable(newCapacity)
    if (size == 0) {
      return newTable
    }
    for (j in 0 until oldCapacity) {
      /*
       * Rehash the bucket using the minimum number of field writes.
       * This is the most subtle and delicate code in the class.
       */
      var e: LinkedEntry<V>? = oldTable[j] ?: continue
      var highBit = e!!.hash and oldCapacity
      var broken: LinkedEntry<V>? = null
      newTable[j or highBit] = e
      var n = e.next
      while (n != null) {
        val nextHighBit = n.hash and oldCapacity
        if (nextHighBit != highBit) {
          if (broken == null) {
            newTable[j or nextHighBit] = n
          } else {
            broken.next = n
          }
          broken = e
          highBit = nextHighBit
        }
        e = n
        n = n.next
      }
      if (broken != null) {
        broken.next = null
      }
    }
    return newTable
  }

  fun remove(key: Long): V? {
    if (key == 0L) {
      return removeNullKey()
    }
    val hash = longHashCode(key)
    val tab = table
    val index = hash and tab.size - 1
    var e = tab[index]
    var prev: LinkedEntry<V>? = null
    while (e != null) {
      if (key == e.key) {
        if (prev == null) {
          tab[index] = e.next
        } else {
          prev.next = e.next
        }
        size--
        postRemove(e)
        return e.value
      }
      prev = e
      e = e.next
    }
    return null
  }

  private fun removeNullKey(): V? {
    val e = entryFor0Key ?: return null
    entryFor0Key = null
    size--
    postRemove(e)
    return e.value
  }

  internal class LinkedEntry<V> {
    val key: Long
    val hash: Int
    var nxt: LinkedEntry<V>?
    var prv: LinkedEntry<V>?
    var value: V?
    var next: LinkedEntry<V>?

    constructor() {
      key = 0
      value = null
      hash = 0
      next = null
      prv = this
      nxt = prv
    }

    constructor(
      key: Long,
      value: V,
      hash: Int,
      next: LinkedEntry<V>?,
      nxt: LinkedEntry<V>?,
      prv: LinkedEntry<V>?
    ) {
      this.key = key
      this.value = value
      this.hash = hash
      this.next = next
      this.nxt = nxt
      this.prv = prv
    }

    override fun equals(o: Any?): Boolean {
      if (o !is LinkedEntry<*>) {
        return false
      }
      val e = o
      return (objectsEqual(e.key, key)
          && objectsEqual(e.value, value))
    }

    override fun hashCode(): Int {
      return longHashCode(key) xor
          if (value == null) 0 else value.hashCode()
    }

    override fun toString(): String {
      return "$key=$value"
    }
  }

  private fun addNewEntry(
    key: Long,
    value: V,
    hash: Int,
    index: Int
  ) {
    val header = header
    // Remove eldest entry if instructed to do so.
    val eldest = header.nxt
    if (eldest !== header && removeEldestEntry()) {
      remove(eldest!!.key)
    }
    // Create new entry, link it on to list, and put it into table
    val oldTail = header.prv
    val newTail = LinkedEntry(
        key, value, hash, table[index], header, oldTail
    )
    header.prv = newTail
    oldTail!!.nxt = header.prv
    table[index] = oldTail.nxt
  }

  private fun addNewEntryFor0Key(value: V) {
    val header = header
    // Remove eldest entry if instructed to do so.
    val eldest = header.nxt
    if (eldest !== header && removeEldestEntry()) {
      remove(eldest!!.key)
    }
    // Create new entry, link it on to list, and put it into table
    val oldTail = header.prv
    val newTail = LinkedEntry(
        0, value, 0, null, header, oldTail
    )
    header.prv = newTail
    oldTail!!.nxt = header.prv
    entryFor0Key = oldTail.nxt
  }

  operator fun get(key: Long): V? {
    // Note: get() moves the key to the front
    val value = getInternal(key)
    if (value != null) {
      hitCount++
    } else {
      missCount++
    }
    return value
  }

  private fun getInternal(key: Long): V? {
    if (key == 0L) {
      val e = entryFor0Key ?: return null
      makeTail(e)
      return e.value
    }
    val hash = longHashCode(key)
    val tab = table
    var e = tab[hash and tab.size - 1]
    while (e != null) {
      val eKey = e.key
      if (eKey == key) {
        makeTail(e)
        return e.value
      }
      e = e.next
    }
    return null
  }

  private fun makeTail(e: LinkedEntry<V>) {
    // Unlink e
    e.prv!!.nxt = e.nxt
    e.nxt!!.prv = e.prv
    // Relink e as tail
    val header = header
    val oldTail = header.prv
    e.nxt = header
    e.prv = oldTail
    header.prv = e
    oldTail!!.nxt = header.prv
  }

  private fun postRemove(e: LinkedEntry<V>) {
    e.prv!!.nxt = e.nxt
    e.nxt!!.prv = e.prv
    e.prv = null
    e.nxt = e.prv // Help the GC (for performance)
  }

  fun evictAll() {
    if (size != 0) {
      Arrays.fill(table, null)
      entryFor0Key = null
      size = 0
    }
    // Clear all links to help GC
    val header = header
    var e = header.nxt
    while (e !== header) {
      val nxt = e!!.nxt
      e.prv = null
      e.nxt = e.prv
      e = nxt
    }
    header.prv = header
    header.nxt = header.prv
  }

  private fun removeEldestEntry(): Boolean {
    return if (size >= maxSize) {
      evictionCount++
      true
    } else {
      false
    }
  }

  override fun toString(): String {
    val accesses = hitCount + missCount
    val hitPercent: Int
    hitPercent = if (accesses != 0) {
      100 * hitCount / accesses
    } else {
      0
    }
    return String.format(
        Locale.US,
        "LruCache[maxSize=%d,hits=%d,misses=%d,hitRate=%d%%]",
        maxSize,
        hitCount,
        missCount,
        hitPercent
    )
  }

  companion object {
    /**
     * Min capacity (other than zero) for a HashMap. Must be a power of two
     * greater than 1 (and less than 1 << 30).
     */
    private const val MINIMUM_CAPACITY = 4

    /**
     * Max capacity for a HashMap. Must be a power of two >= MINIMUM_CAPACITY.
     */
    private const val MAXIMUM_CAPACITY = 1 shl 30
    private fun roundUpToPowerOfTwo(i: Int): Int {
      var i = i
      i-- // If input is a power of two, shift its high-order bit right.
      // "Smear" the high-order bit all the way to the right.
      i = i or (i ushr 1)
      i = i or (i ushr 2)
      i = i or (i ushr 4)
      i = i or (i ushr 8)
      i = i or (i ushr 16)
      return i + 1
    }

    private fun objectsEqual(
      a: Any?,
      b: Any?
    ): Boolean {
      return a === b || a != null && a == b
    }

    private fun longHashCode(value: Long): Int {
      return (value xor (value ushr 32)).toInt()
    }
  }

  init {
    require(maxSize > 0) { "Max size: $maxSize" }
    this.maxSize = maxSize
    var initialCapacity = maxSize
    initialCapacity = if (initialCapacity < MINIMUM_CAPACITY) {
      MINIMUM_CAPACITY
    } else if (initialCapacity > MAXIMUM_CAPACITY) {
      MAXIMUM_CAPACITY
    } else {
      roundUpToPowerOfTwo(initialCapacity)
    }
    makeTable(initialCapacity)
    header = LinkedEntry()
  }
}