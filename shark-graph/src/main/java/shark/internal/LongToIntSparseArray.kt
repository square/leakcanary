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

import shark.internal.SparseArrayUtils.appendInt
import shark.internal.SparseArrayUtils.appendLong
import shark.internal.SparseArrayUtils.binarySearch
import shark.internal.SparseArrayUtils.insertInt
import shark.internal.SparseArrayUtils.insertLong

/**
 * Same as [LongToLongSparseArray] but long to int instead.
 */
internal class LongToIntSparseArray(initialCapacity: Int) : Cloneable {
  private var keys: LongArray
  private var values: IntArray

  var size: Int = 0
    private set

  init {
    keys = LongArray(initialCapacity)
    values = IntArray(initialCapacity)
    size = 0
  }

  operator fun get(key: Long): Int {
    val i = binarySearch(keys, size, key)

    return if (i < 0 || values[i] == DELETED_INT) {
      DELETED_INT
    } else {
      values[i]
    }
  }

  operator fun set(
    key: Long,
    value: Int
  ) {
    require(value != DELETED_INT) {
      "$DELETED_INT is a magic value that indicates a deleted entry"
    }

    if (size != 0 && key <= keys[size - 1]) {
      insert(key, value)
      return
    }

    keys = appendLong(keys, size, key)
    values = appendInt(values, size, value)
    size++
  }

  private fun insert(
    key: Long,
    value: Int
  ) {
    if (value == DELETED_INT) {
      throw IllegalArgumentException("$DELETED_INT is a special value")
    }
    var i = binarySearch(keys, size, key)

    if (i >= 0) {
      values[i] = value
    } else {
      i = i.inv()

      if (i < size && values[i] == DELETED_INT) {
        keys[i] = key
        values[i] = value
        return
      }

      keys = insertLong(keys, size, i, key)
      values = insertInt(values, size, i, value)
      size++
    }
  }

  companion object {
    private const val DELETED_INT = 0
  }
}