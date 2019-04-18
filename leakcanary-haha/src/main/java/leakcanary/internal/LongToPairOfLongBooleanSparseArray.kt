package leakcanary.internal

import leakcanary.internal.SparseArrayUtils.appendBoolean
import leakcanary.internal.SparseArrayUtils.appendLong
import leakcanary.internal.SparseArrayUtils.binarySearch
import leakcanary.internal.SparseArrayUtils.insertBoolean
import leakcanary.internal.SparseArrayUtils.insertLong

/**
 * Same as [LongToLongSparseArray] but long to pair(long, boolean) instead.
 * Yes, this is weird.
 */
class LongToPairOfLongBooleanSparseArray(initialCapacity: Int) : Cloneable {
  private var keys: LongArray
  private var firsts: LongArray
  private var seconds: BooleanArray

  var size: Int = 0
    private set

  init {
    keys = LongArray(initialCapacity)
    firsts = LongArray(initialCapacity)
    seconds = BooleanArray(initialCapacity)
    size = 0
  }

  operator fun get(key: Long): Pair<Long, Boolean> {
    val i = binarySearch(keys, size, key)

    return if (i < 0 || firsts[i] == DELETED_LONG) {
      DELETED_LONG to false
    } else {
      firsts[i] to seconds[i]
    }
  }

  fun first(key: Long): Long {
    val i = binarySearch(keys, size, key)

    return if (i < 0 || firsts[i] == DELETED_LONG) {
      DELETED_LONG
    } else {
      firsts[i]
    }
  }

  operator fun set(
    key: Long,
    first: Long,
    second: Boolean
  ) {
    require(first != DELETED_LONG) {
      "$DELETED_LONG is a magic value that indicates a deleted entry"
    }

    if (size != 0 && key <= keys[size - 1]) {
      insert(key, first, second)
      return
    }

    keys = appendLong(keys, size, key)
    firsts = appendLong(firsts, size, first)
    seconds = appendBoolean(seconds, size, second)
    size++
  }

  private fun insert(
    key: Long,
    first: Long,
    second: Boolean
  ) {
    if (first == DELETED_LONG) {
      throw IllegalArgumentException("$DELETED_LONG is a special first")
    }
    var i = binarySearch(keys, size, key)

    if (i >= 0) {
      firsts[i] = first
      seconds[i] = second
    } else {
      i = i.inv()

      if (i < size && firsts[i] == DELETED_LONG) {
        keys[i] = key
        firsts[i] = first
        seconds[i] = second
        return
      }

      keys = insertLong(keys, size, i, key)
      firsts = insertLong(firsts, size, i, first)
      seconds = insertBoolean(seconds, size, i, second)
      size++
    }
  }

  companion object {
    private const val DELETED_LONG: Long = 0
  }
}