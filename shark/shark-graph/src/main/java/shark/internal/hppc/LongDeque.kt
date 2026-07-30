package shark.internal.hppc

/**
 * A FIFO queue of longs, backed by a growable circular buffer.
 *
 * This exists because neither the JDK nor androidx.collection provides a primitive long queue:
 * [java.util.ArrayDeque] boxes every element and androidx.collection's `LongList` is a plain
 * array with no head index, so removing from its head is O(size).
 */
internal class LongDeque(expectedElements: Int = 4) {

  private var elements = LongArray(expectedElements.coerceAtLeast(1))

  /** Index of the first element, meaningless when [size] is 0. */
  private var head = 0

  var size = 0
    private set

  fun isEmpty() = size == 0

  fun isNotEmpty() = size != 0

  fun add(element: Long) {
    if (size == elements.size) {
      grow()
    }
    var tail = head + size
    if (tail >= elements.size) {
      tail -= elements.size
    }
    elements[tail] = element
    size++
  }

  operator fun plusAssign(element: Long) = add(element)

  fun poll(): Long {
    check(size > 0) {
      "Cannot poll an empty deque"
    }
    val element = elements[head]
    head++
    if (head == elements.size) {
      head = 0
    }
    size--
    return element
  }

  fun clear() {
    head = 0
    size = 0
  }

  private fun grow() {
    // Doubling in Int arithmetic would wrap negative at 2^30 elements, and LongArray() would then
    // throw a NegativeArraySizeException that says nothing about what hit its limit.
    val grownSize = elements.size.toLong() * 2
    check(grownSize <= Int.MAX_VALUE) {
      "Cannot hold more than ${elements.size} elements: growing doubles the backing array, and an " +
        "array of $grownSize longs is past what an Int index can address"
    }
    val grown = LongArray(grownSize.toInt())
    // Unwrap the circular buffer into the new array, so that head becomes 0 again.
    val untilEnd = elements.size - head
    elements.copyInto(grown, destinationOffset = 0, startIndex = head)
    elements.copyInto(grown, destinationOffset = untilEnd, startIndex = 0, endIndex = head)
    elements = grown
    head = 0
  }
}
