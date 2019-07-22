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
import shark.internal.SparseArrayUtils.appendObject
import shark.internal.SparseArrayUtils.binarySearch
import shark.internal.SparseArrayUtils.insertLong
import shark.internal.SparseArrayUtils.insertObject

/**
 * Same as [LongToLongSparseArray] but long to object instead.
 */
internal class LongToObjectSparseArray<T>(initialCapacity: Int) : Cloneable {
  private var keys: LongArray
  private var values: Array<T?>

  var size: Int = 0
    private set

  init {
    keys = LongArray(initialCapacity)
    @Suppress("UNCHECKED_CAST")
    values = arrayOfNulls<Any?>(initialCapacity) as Array<T?>
    size = 0
  }

  operator fun get(key: Long): T? {
    val i = binarySearch(keys, size, key)

    return if (i < 0 || values[i] == null) {
      null
    } else {
      values[i]!!
    }
  }

  operator fun set(
    key: Long,
    value: T
  ) {
    if (size != 0 && key <= keys[size - 1]) {
      insert(key, value)
      return
    }

    keys = appendLong(keys, size, key)
    values = appendObject(values, size, value)
    size++
  }

  fun entrySequence(): Sequence<Pair<Long, T>> {
    return (0..size).asSequence().filter { values[it] != null }.map { keys[it] to values[it]!! }
  }

  private fun insert(
    key: Long,
    value: T
  ) {
    var i = binarySearch(keys, size, key)

    if (i >= 0) {
      values[i] = value
    } else {
      i = i.inv()

      if (i < size && values[i] == null) {
        keys[i] = key
        values[i] = value
        return
      }

      keys = insertLong(keys, size, i, key)
      values = insertObject(values, size, i, value)
      size++
    }
  }

}