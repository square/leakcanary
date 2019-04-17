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
class LongToStringSparseArray(initialCapacity: Int) : Cloneable {
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