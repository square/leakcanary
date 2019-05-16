package leakcanary.internal.hppc

import java.util.Locale

/**
 * Code from com.carrotsearch.hppc.LongScatterSet copy pasted, inlined and converted to Kotlin.
 *
 * See https://github.com/carrotsearch/hppc .
 */
internal class LongScatterSet {
  /** The hash array holding keys.  */
  private var keys: LongArray = longArrayOf()
  /**
   * The number of stored keys (assigned key slots), excluding the special
   * "empty" key, if any.
   *
   * @see .size
   * @see .hasEmptyKey
   */
  private var assigned = 0
  /**
   * Mask for slot scans in [.keys].
   */
  private var mask = 0

  /**
   * Expand (rehash) [.keys] when [.assigned] hits this value.
   */
  private var resizeAt = 0
  /**
   * Special treatment for the "empty slot" key marker.
   */
  private var hasEmptyKey = false
  /**
   * The load factor for [.keys].
   */
  private val loadFactor = 0.75

  init {
    ensureCapacity(4)
  }

  private fun hashKey(key: Long): Int {
    return HHPC.mixPhi(key)
  }

  operator fun plusAssign(key: Long) {
    add(key)
  }

  fun add(key: Long): Boolean {
    if (key == 0L) {
      val added = !hasEmptyKey
      hasEmptyKey = true
      return added
    } else {
      val keys = this.keys
      val mask = this.mask
      var slot = hashKey(key) and mask

      var existing = keys[slot]
      while (existing != 0L) {
        if (existing == key) {
          return false
        }
        slot = slot + 1 and mask
        existing = keys[slot]
      }

      if (assigned == resizeAt) {
        allocateThenInsertThenRehash(slot, key)
      } else {
        keys[slot] = key
      }

      assigned++
      return true
    }
  }

  operator fun contains(key: Long): Boolean {
    if (key == 0L) {
      return hasEmptyKey
    } else {
      val keys = this.keys
      val mask = this.mask
      var slot = hashKey(key) and mask
      var existing = keys[slot]
      while (existing != 0L) {
        if (existing == key) {
          return true
        }
        slot = slot + 1 and mask
        existing = keys[slot]
      }
      return false
    }
  }

  fun release() {
    assigned = 0
    hasEmptyKey = false
    allocateBuffers(HHPC.minBufferSize(4, loadFactor))
  }

  fun ensureCapacity(expectedElements: Int) {
    if (expectedElements > resizeAt) {
      val prevKeys = this.keys
      allocateBuffers(HHPC.minBufferSize(expectedElements, loadFactor))
      if (size() != 0) {
        rehash(prevKeys)
      }
    }
  }

  fun size(): Int {
    return assigned + if (hasEmptyKey) 1 else 0
  }

  private fun rehash(fromKeys: LongArray) {
    // Rehash all stored keys into the new buffers.
    val keys = this.keys
    val mask = this.mask
    var existing: Long
    var i = fromKeys.size - 1
    while (--i >= 0) {
      existing = fromKeys[i]
      if (existing != 0L) {
        var slot = hashKey(existing) and mask
        while (keys[slot] != 0L) {
          slot = slot + 1 and mask
        }
        keys[slot] = existing
      }
    }
  }

  /**
   * Allocate new internal buffers. This method attempts to allocate
   * and assign internal buffers atomically (either allocations succeed or not).
   */
  private fun allocateBuffers(arraySize: Int) {
    // Ensure no change is done if we hit an OOM.
    val prevKeys = this.keys
    try {
      val emptyElementSlot = 1
      this.keys = LongArray(arraySize + emptyElementSlot)
    } catch (e: OutOfMemoryError) {
      this.keys = prevKeys
      throw RuntimeException(
          String.format(
              Locale.ROOT,
              "Not enough memory to allocate buffers for rehashing: %,d -> %,d",
              size(),
              arraySize
          ), e
      )
    }

    this.resizeAt = HHPC.expandAtCount(arraySize, loadFactor)
    this.mask = arraySize - 1
  }

  private fun allocateThenInsertThenRehash(
    slot: Int,
    pendingKey: Long
  ) {
    // Try to allocate new buffers first. If we OOM, we leave in a consistent state.
    val prevKeys = this.keys
    allocateBuffers(HHPC.nextBufferSize(mask + 1, size(), loadFactor))

    // We have succeeded at allocating new data so insert the pending key/value at
    // the free slot in the old arrays before rehashing.
    prevKeys[slot] = pendingKey

    // Rehash old keys, including the pending key.
    rehash(prevKeys)
  }
}