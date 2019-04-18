package leakcanary.internal

import leakcanary.internal.SparseArrayUtils.appendInt
import leakcanary.internal.SparseArrayUtils.appendLong
import leakcanary.internal.SparseArrayUtils.binarySearch
import leakcanary.internal.SparseArrayUtils.insertInt
import leakcanary.internal.SparseArrayUtils.insertLong

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

  fun compact() {
    val initialSize = size
    var compactedSize = 0
    val keys = keys
    val values = values
    for (i in 0 until initialSize) {
      val value = values[i]
      if (value != DELETED_INT) {
        if (i != compactedSize) {
          keys[compactedSize] = keys[i]
          values[compactedSize] = value
          values[i] = DELETED_INT
        }
        compactedSize++
      }
    }
    if (compactedSize != initialSize) {
      size = compactedSize
      this.keys = LongArray(compactedSize)
      System.arraycopy(keys, 0, this.keys, 0, compactedSize)
      this.values = IntArray(compactedSize)
      System.arraycopy(values, 0, this.values, 0, compactedSize)
    }
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