/*
 *  Copyright 2010-2013, Carrot Search s.c., Boznicza 11/56, Poznan, Poland
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package shark.internal.hppc

import java.util.Locale

/**
 * Code from com.carrotsearch.hppc.LongLongScatterMap copy pasted, inlined and converted to Kotlin.
 *
 * See https://github.com/carrotsearch/hppc .
 */
internal class LongObjectScatterMap<T> {
  /**
   * The array holding keys.
   */
  private var keys: LongArray = longArrayOf()

  /**
   * The array holding values.
   */
  @Suppress("UNCHECKED_CAST")
  private var values: Array<T?> = emptyArray<Any?>() as Array<T?>

  /**
   * The number of stored keys (assigned key slots), excluding the special
   * "empty" key, if any (use [.size] instead).
   *
   * @see .size
   */
  private var assigned: Int = 0

  /**
   * Mask for slot scans in [.keys].
   */
  private var mask: Int = 0

  /**
   * Expand (rehash) [.keys] when [.assigned] hits this value.
   */
  private var resizeAt: Int = 0

  /**
   * Special treatment for the "empty slot" key marker.
   */
  private var hasEmptyKey: Boolean = false

  /**
   * The load factor for [.keys].
   */
  private var loadFactor: Double = 0.75

  val isEmpty: Boolean
    get() = size == 0

  init {
    ensureCapacity(4)
  }

  operator fun set(
    key: Long,
    value: T
  ): T? {
    val mask = this.mask
    if (key == 0L) {
      hasEmptyKey = true
      val previousValue = values[mask + 1]
      values[mask + 1] = value
      return previousValue
    } else {
      val keys = this.keys
      var slot = hashKey(key) and mask

      var existing = keys[slot]
      while (existing != 0L) {
        if (existing == key) {
          val previousValue = values[slot]
          values[slot] = value
          return previousValue
        }
        slot = slot + 1 and mask
        existing = keys[slot]
      }

      if (assigned == resizeAt) {
        allocateThenInsertThenRehash(slot, key, value)
      } else {
        keys[slot] = key
        values[slot] = value
      }

      assigned++
      return null
    }
  }

  fun remove(key: Long): T? {
    val mask = this.mask
    if (key == 0L) {
      hasEmptyKey = false
      val previousValue = values[mask + 1]
      values[mask + 1] = null
      return previousValue
    } else {
      val keys = this.keys
      var slot = hashKey(key) and mask

      var existing = keys[slot]
      while (existing != 0L) {
        if (existing == key) {
          val previousValue = values[slot]
          shiftConflictingKeys(slot)
          return previousValue
        }
        slot = slot + 1 and mask
        existing = keys[slot]
      }

      return null
    }
  }

  operator fun get(key: Long): T? {
    if (key == 0L) {
      return if (hasEmptyKey) values[mask + 1] else null
    } else {
      val keys = this.keys
      val mask = this.mask
      var slot = hashKey(key) and mask

      var existing = keys[slot]
      while (existing != 0L) {
        if (existing == key) {
          return values[slot]
        }
        slot = slot + 1 and mask
        existing = keys[slot]
      }

      return null
    }
  }

  fun entrySequence(): Sequence<LongObjectPair<T>> {
    val max = mask + 1
    var slot = -1
    return generateSequence {
      if (slot < max) {
        var existing: Long
        slot++
        while (slot < max) {
          existing = keys[slot]
          if (existing != 0L) {
            return@generateSequence existing to values[slot]!!
          }
          slot++
        }
      }
      if (slot == max && hasEmptyKey) {
        slot++
        return@generateSequence 0L to values[max]!!
      }
      return@generateSequence null
    }
  }

  fun containsKey(key: Long): Boolean {
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

  val size: Int
    get() {
      return assigned + if (hasEmptyKey) 1 else 0
    }

  fun ensureCapacity(expectedElements: Int) {
    if (expectedElements > resizeAt) {
      val prevKeys = this.keys
      val prevValues = this.values
      allocateBuffers(HHPC.minBufferSize(expectedElements, loadFactor))
      if (!isEmpty) {
        rehash(prevKeys, prevValues)
      }
    }
  }

  private fun hashKey(key: Long): Int {
    return HHPC.mixPhi(key)
  }

  /**
   * Rehash from old buffers to new buffers.
   */
  private fun rehash(
    fromKeys: LongArray,
    fromValues: Array<T?>
  ) {
    // Rehash all stored key/value pairs into the new buffers.
    val keys = this.keys
    val values = this.values
    val mask = this.mask
    var existing: Long

    // Copy the zero element's slot, then rehash everything else.
    var from = fromKeys.size - 1
    keys[keys.size - 1] = fromKeys[from]
    values[values.size - 1] = fromValues[from]
    while (--from >= 0) {
      existing = fromKeys[from]
      if (existing != 0L) {
        var slot = hashKey(existing) and mask
        while (keys[slot] != 0L) {
          slot = slot + 1 and mask
        }
        keys[slot] = existing
        values[slot] = fromValues[from]
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
    val prevValues = this.values
    try {
      val emptyElementSlot = 1
      this.keys = LongArray(arraySize + emptyElementSlot)
      @Suppress("UNCHECKED_CAST")
      this.values = arrayOfNulls<Any?>(arraySize + emptyElementSlot) as Array<T?>
    } catch (e: OutOfMemoryError) {
      this.keys = prevKeys
      this.values = prevValues
      throw RuntimeException(
          String.format(
              Locale.ROOT,
              "Not enough memory to allocate buffers for rehashing: %,d -> %,d",
              this.mask + 1,
              arraySize
          ), e
      )
    }

    this.resizeAt = HHPC.expandAtCount(arraySize, loadFactor)
    this.mask = arraySize - 1
  }

  /**
   * This method is invoked when there is a new key/ value pair to be inserted into
   * the buffers but there is not enough empty slots to do so.
   *
   * New buffers are allocated. If this succeeds, we know we can proceed
   * with rehashing so we assign the pending element to the previous buffer
   * (possibly violating the invariant of having at least one empty slot)
   * and rehash all keys, substituting new buffers at the end.
   */
  private fun allocateThenInsertThenRehash(
    slot: Int,
    pendingKey: Long,
    pendingValue: T
  ) {

    // Try to allocate new buffers first. If we OOM, we leave in a consistent state.
    val prevKeys = this.keys
    val prevValues = this.values
    allocateBuffers(HHPC.nextBufferSize(mask + 1, size, loadFactor))

    // We have succeeded at allocating new data so insert the pending key/value at
    // the free slot in the old arrays before rehashing.
    prevKeys[slot] = pendingKey
    prevValues[slot] = pendingValue

    // Rehash old keys, including the pending key.
    rehash(prevKeys, prevValues)
  }

  /**
   * Shift all the slot-conflicting keys and values allocated to
   * (and including) `slot`.
   */
  private fun shiftConflictingKeys(gapSlotArg: Int) {
    var gapSlot = gapSlotArg
    val keys = this.keys
    val values = this.values
    val mask = this.mask

    // Perform shifts of conflicting keys to fill in the gap.
    var distance = 0
    while (true) {
      val slot = gapSlot + ++distance and mask
      val existing = keys[slot]
      if (existing == 0L) {
        break
      }

      val idealSlot = hashKey(existing)
      val shift = slot - idealSlot and mask
      if (shift >= distance) {
        // Entry at this position was originally at or before the gap slot.
        // Move the conflict-shifted entry to the gap's position and repeat the procedure
        // for any entries to the right of the current position, treating it
        // as the new gap.
        keys[gapSlot] = existing
        values[gapSlot] = values[slot]
        gapSlot = slot
        distance = 0
      }
    }

    // Mark the last found gap slot without a conflict as empty.
    keys[gapSlot] = 0L
    values[gapSlot] = null
    assigned--
  }
}
