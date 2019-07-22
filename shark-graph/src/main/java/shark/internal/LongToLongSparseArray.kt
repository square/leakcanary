/*
 * Copyright (C) 2009 The Android Open Source Project
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

import shark.internal.SparseArrayUtils.appendLong
import shark.internal.SparseArrayUtils.binarySearch
import shark.internal.SparseArrayUtils.insertLong

/**
 * Based on android.util.LongSparseArray, but with several tweaks:
 *
 * - Values array is a LongArray
 * - No compaction, ever. This object can only keep growing, until it is garbage collected. Note
 * that there is no remove() method.
 */
internal class LongToLongSparseArray(initialCapacity: Int) : Cloneable {
  private var keys: LongArray
  private var values: LongArray

  var size: Int = 0
    private set

  init {
    keys = LongArray(initialCapacity)
    values = LongArray(initialCapacity)
    size = 0
  }

  operator fun get(key: Long): Long {
    val i = binarySearch(keys, size, key)

    return if (i < 0 || values[i] == DELETED_LONG) {
      DELETED_LONG
    } else {
      values[i]
    }
  }

  fun getKey(value: Long): Long? {
    for (i in 0 until size) {
      if (values[i] == value) {
        return keys[i]
      }
    }
    return null
  }

  operator fun set(
    key: Long,
    value: Long
  ) {
    require(value != DELETED_LONG) {
      "$DELETED_LONG is a magic value that indicates a deleted entry"
    }

    if (size != 0 && key <= keys[size - 1]) {
      insert(key, value)
      return
    }

    keys = appendLong(keys, size, key)
    values = appendLong(values, size, value)
    size++
  }

  private fun insert(
    key: Long,
    value: Long
  ) {
    if (value == DELETED_LONG) {
      throw IllegalArgumentException("$DELETED_LONG is a special value")
    }
    var i = binarySearch(keys, size, key)

    if (i >= 0) {
      values[i] = value
    } else {
      i = i.inv()

      if (i < size && values[i] == DELETED_LONG) {
        keys[i] = key
        values[i] = value
        return
      }

      keys = insertLong(keys, size, i, key)
      values = insertLong(values, size, i, value)
      size++
    }
  }

  companion object {
    private const val DELETED_LONG: Long = 0
  }
}