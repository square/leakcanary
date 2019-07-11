package leakcanary.internal

internal object SparseArrayUtils {

  fun insertInt(
    array: IntArray,
    currentSize: Int,
    index: Int,
    element: Int
  ): IntArray {
    if (currentSize + 1 <= array.size) {
      System.arraycopy(array, index, array, index + 1, currentSize - index)
      array[index] = element
      return array
    }

    val newArray = IntArray(growSize(currentSize))
    System.arraycopy(array, 0, newArray, 0, index)
    newArray[index] = element
    System.arraycopy(array, index, newArray, index + 1, array.size - index)
    return newArray
  }

  fun <T> insertObject(
    array: Array<T?>,
    currentSize: Int,
    index: Int,
    element: T
  ): Array<T?> {
    if (currentSize + 1 <= array.size) {
      System.arraycopy(array, index, array, index + 1, currentSize - index)
      array[index] = element
      return array
    }

    @Suppress("UNCHECKED_CAST")
    val newArray = arrayOfNulls<Any?>(growSize(currentSize)) as Array<T?>
    System.arraycopy(array, 0, newArray, 0, index)
    newArray[index] = element
    System.arraycopy(array, index, newArray, index + 1, array.size - index)
    return newArray
  }

  fun insertLong(
    array: LongArray,
    currentSize: Int,
    index: Int,
    element: Long
  ): LongArray {
    if (currentSize + 1 <= array.size) {
      System.arraycopy(array, index, array, index + 1, currentSize - index)
      array[index] = element
      return array
    }

    val newArray = LongArray(growSize(currentSize))
    System.arraycopy(array, 0, newArray, 0, index)
    newArray[index] = element
    System.arraycopy(array, index, newArray, index + 1, array.size - index)
    return newArray
  }

  fun insertString(
    array: Array<String?>,
    currentSize: Int,
    index: Int,
    element: String?
  ): Array<String?> {
    if (currentSize + 1 <= array.size) {
      System.arraycopy(array, index, array, index + 1, currentSize - index)
      array[index] = element
      return array
    }

    val newArray = arrayOfNulls<String>(growSize(currentSize))
    System.arraycopy(array, 0, newArray, 0, index)
    newArray[index] = element
    System.arraycopy(array, index, newArray, index + 1, array.size - index)
    return newArray
  }

  fun appendInt(
    array: IntArray,
    currentSize: Int,
    element: Int
  ): IntArray {
    var returnedArray = array
    if (currentSize + 1 > returnedArray.size) {
      val newArray = IntArray(growSize(currentSize))
      System.arraycopy(returnedArray, 0, newArray, 0, currentSize)
      returnedArray = newArray
    }
    returnedArray[currentSize] = element
    return returnedArray
  }

  fun appendLong(
    array: LongArray,
    currentSize: Int,
    element: Long
  ): LongArray {
    var returnedArray = array
    if (currentSize + 1 > returnedArray.size) {
      val newArray = LongArray(growSize(currentSize))
      System.arraycopy(returnedArray, 0, newArray, 0, currentSize)
      returnedArray = newArray
    }
    returnedArray[currentSize] = element
    return returnedArray
  }

  fun <T> appendObject(
    array: Array<T?>,
    currentSize: Int,
    element: T
  ): Array<T?> {
    var returnedArray = array
    if (currentSize + 1 > returnedArray.size) {
      @Suppress("UNCHECKED_CAST")
      val newArray = arrayOfNulls<Any?>(growSize(currentSize)) as Array<T?>
      System.arraycopy(returnedArray, 0, newArray, 0, currentSize)
      returnedArray = newArray
    }
    returnedArray[currentSize] = element
    return returnedArray
  }

  fun appendString(
    array: Array<String?>,
    currentSize: Int,
    element: String?
  ): Array<String?> {
    var returnedArray = array
    if (currentSize + 1 > returnedArray.size) {

      val newArray = arrayOfNulls<String>(growSize(currentSize))
      System.arraycopy(returnedArray, 0, newArray, 0, currentSize)
      returnedArray = newArray
    }
    returnedArray[currentSize] = element
    return returnedArray
  }

  /**
   * Android array helpers use 2. C++ uses 2. ArrayList uses 1.5
   * We're dealing with large arrays here so being conservative is good for memory.
   */
  private fun growSize(currentSize: Int) = (currentSize * 1.5).toInt()

  fun binarySearch(
    array: LongArray?,
    size: Int,
    value: Long
  ): Int {
    var lo = 0
    var hi = size - 1

    while (lo <= hi) {
      val mid = (lo + hi).ushr(1)
      val midVal = array!![mid]

      when {
        midVal < value -> lo = mid + 1
        midVal > value -> hi = mid - 1
        else -> return mid  // value found
      }
    }
    return lo.inv()  // value not present
  }
}