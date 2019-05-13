package leakcanary.internal

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
    values = arrayOfNulls(initialCapacity)
    size = 0
  }

  operator fun get(key: Long): String? {
    val i = binarySearch(keys, size, key)

    return if (i < 0 || values[i] == null) {
      null
    } else {
      values[i]
    }
  }

  fun getKey(value: String): Long? {
    for (i in 0 until size) {
      if (values[i] == value) {
        return keys[i]
      }
    }
    return null
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
      values = insertString(values, size, i, value)
      size++
    }
  }
}