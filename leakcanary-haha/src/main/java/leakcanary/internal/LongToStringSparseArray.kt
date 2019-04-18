package leakcanary.internal

import leakcanary.internal.SparseArrayUtils.DELETED_STRING
import leakcanary.internal.SparseArrayUtils.FILL_WITH_DELETED
import leakcanary.internal.SparseArrayUtils.appendLong
import leakcanary.internal.SparseArrayUtils.appendString
import leakcanary.internal.SparseArrayUtils.binarySearch
import leakcanary.internal.SparseArrayUtils.insertLong
import leakcanary.internal.SparseArrayUtils.insertString

/**
 * Same as [LongToLongSparseArray] but long to string instead.
 */
internal class LongToStringSparseArray(initialCapacity: Int) : Cloneable {
  private var keys: LongArray
  private var values: Array<String?>

  var size: Int = 0
    private set

  init {
    keys = LongArray(initialCapacity)
    values = Array(initialCapacity, FILL_WITH_DELETED)
    size = 0
  }

  operator fun get(key: Long): String? {
    val i = binarySearch(keys, size, key)

    return if (i < 0 || values[i] == DELETED_STRING) {
      DELETED_STRING
    } else {
      values[i]
    }
  }

  fun compact() {
    val initialSize = size
    var compactedSize = 0
    val keys = keys
    val values = values
    for (i in 0 until initialSize) {
      val value = values[i]
      if (value != DELETED_STRING) {
        if (i != compactedSize) {
          keys[compactedSize] = keys[i]
          values[compactedSize] = value
          values[i] = DELETED_STRING
        }
        compactedSize++
      }
    }
    if (compactedSize != initialSize) {
      size = compactedSize
      this.keys = LongArray(compactedSize)
      System.arraycopy(keys, 0, this.keys, 0, compactedSize)
      this.values = Array(compactedSize, FILL_WITH_DELETED)
      System.arraycopy(values, 0, this.values, 0, compactedSize)
    }
  }

  operator fun set(
    key: Long,
    value: String
  ) {
    if (size != 0 && key <= keys[size - 1]) {
      insert(key, value)
      return
    }

    keys = appendLong(keys, size, key)
    values = appendString(values, size, value)
    size++
  }

  private fun insert(
    key: Long,
    value: String
  ) {
    if (value == DELETED_STRING) {
      throw IllegalArgumentException("$DELETED_STRING is a special value")
    }
    var i = binarySearch(keys, size, key)

    if (i >= 0) {
      values[i] = value
    } else {
      i = i.inv()

      if (i < size && values[i] == DELETED_STRING) {
        keys[i] = key
        values[i] = value
        return
      }

      keys = insertLong(keys, size, i, key)
      values = insertString(values, size, i, value)
      size++
    }
  }

  companion object {

  }
}