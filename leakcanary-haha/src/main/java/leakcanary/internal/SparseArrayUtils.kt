package leakcanary.internal

object SparseArrayUtils {

  val DELETED_STRING: String? = null

  val FILL_WITH_DELETED: (Int) -> String? = {
    null
  }

  fun insertByte(
    array: ByteArray,
    currentSize: Int,
    index: Int,
    element: Byte
  ): ByteArray {
    if (currentSize + 1 <= array.size) {
      System.arraycopy(array, index, array, index + 1, currentSize - index)
      array[index] = element
      return array
    }
    val newArray = ByteArray(currentSize * 2)
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

    val newArray = LongArray(currentSize * 2)
    System.arraycopy(array, 0, newArray, 0, index)
    newArray[index] = element
    System.arraycopy(array, index, newArray, index + 1, array.size - index)
    return newArray
  }

  fun appendByte(
    array: ByteArray,
    currentSize: Int,
    element: Byte
  ): ByteArray {
    var returnedArray = array

    if (currentSize + 1 > returnedArray.size) {
      val newArray = ByteArray(currentSize * 2)
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
      val newArray = LongArray(currentSize * 2)
      System.arraycopy(returnedArray, 0, newArray, 0, currentSize)
      returnedArray = newArray
    }
    returnedArray[currentSize] = element
    return returnedArray
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

    val newArray = Array(currentSize * 2, FILL_WITH_DELETED)
    System.arraycopy(array, 0, newArray, 0, index)
    newArray[index] = element
    System.arraycopy(array, index, newArray, index + 1, array.size - index)
    return newArray
  }

  fun appendString(
    array: Array<String?>,
    currentSize: Int,
    element: String?
  ): Array<String?> {
    var returnedArray = array
    if (currentSize + 1 > returnedArray.size) {

      val newArray = Array(currentSize * 2, FILL_WITH_DELETED)
      System.arraycopy(returnedArray, 0, newArray, 0, currentSize)
      returnedArray = newArray
    }
    returnedArray[currentSize] = element
    return returnedArray
  }

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