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

package shark.internal.hppcshark

import java.util.Locale

/**
 * Code from com.carrotsearch.hppc.LongScatterSet copy pasted, inlined and converted to Kotlin.
 *
 * See https://github.com/carrotsearch/hppc .
 */
internal class LongScatterSet(expectedElements: Int = 4) {
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
    ensureCapacity(expectedElements)
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

  fun remove(key: Long): Boolean {
    return if (key == 0L) {
      val hadEmptyKey = hasEmptyKey
      hasEmptyKey = false
      hadEmptyKey
    } else {
      val keys = this.keys
      val mask = this.mask
      var slot = hashKey(key) and mask
      var existing: Long = keys[slot]
      while (existing != 0L) {
        if (existing == key) {
          shiftConflictingKeys(slot)
          return true
        }
        slot = slot + 1 and mask
        existing = keys[slot]
      }
      false
    }
  }

  /**
   * Shift all the slot-conflicting keys allocated to (and including) `slot`.
   */
  private fun shiftConflictingKeys(inputGapSlot: Int) {
    var gapSlot = inputGapSlot
    val keys = keys
    val mask = mask
    // Perform shifts of conflicting keys to fill in the gap.
    var distance = 0
    while (true) {
      val slot = (gapSlot + (++distance)) and mask
      val existing = keys[slot]
      if (existing == 0L) {
        break
      }
      val idealSlot = hashKey(existing)
      val shift = (slot - idealSlot) and mask
      if (shift >= distance) {
        // Entry at this position was originally at or before the gap slot.
        // Move the conflict-shifted entry to the gap's position and repeat the procedure
        // for any entries to the right of the current position, treating it
        // as the new gap.
        keys[gapSlot] = existing
        gapSlot = slot
        distance = 0
      }
    }
    // Mark the last found gap slot without a conflict as empty.
    keys[gapSlot] = 0L
    assigned--
  }

  fun release() {
    assigned = 0
    hasEmptyKey = false
    allocateBuffers(HHPC.minBufferSize(4, loadFactor))
  }

  fun ensureCapacity(expectedElements: Int) {
    if (expectedElements > resizeAt) {
      val prevKeys = this.keys
      allocateBuffers(
          HHPC.minBufferSize(
              expectedElements, loadFactor
          )
      )
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

    this.resizeAt =
      HHPC.expandAtCount(arraySize, loadFactor)
    this.mask = arraySize - 1
  }

  private fun allocateThenInsertThenRehash(
    slot: Int,
    pendingKey: Long
  ) {
    // Try to allocate new buffers first. If we OOM, we leave in a consistent state.
    val prevKeys = this.keys
    allocateBuffers(
        HHPC.nextBufferSize(
            mask + 1, size(), loadFactor
        )
    )

    // We have succeeded at allocating new data so insert the pending key/value at
    // the free slot in the old arrays before rehashing.
    prevKeys[slot] = pendingKey

    // Rehash old keys, including the pending key.
    rehash(prevKeys)
  }
}