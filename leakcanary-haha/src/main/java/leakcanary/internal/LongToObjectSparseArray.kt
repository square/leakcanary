package leakcanary.internal

import leakcanary.internal.SparseArrayUtils.appendLong
import leakcanary.internal.SparseArrayUtils.appendObject
import leakcanary.internal.SparseArrayUtils.binarySearch
import leakcanary.internal.SparseArrayUtils.insertLong
import leakcanary.internal.SparseArrayUtils.insertObject

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

  operator fun get(key: Long): T {
    val i = binarySearch(keys, size, key)

    return if (i < 0 || values[i] == null) {
      throw NullPointerException("Key $key not set")
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