/*
 *  Copyright 2010-2013, Carrot Search s.c., Boznicza 11/56, Poznan, Poland
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package shark.internal.hppc

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Code from https://github.com/carrotsearch/hppc copy pasted, inlined and converted to Kotlin.
 */
internal object HPPC {

  private const val PHI_C64 = -0x61c8864680b583ebL

  fun mixPhi(k: Long): Int {
    val h = k * PHI_C64
    return (h xor h.ushr(32)).toInt()
  }

  private const val MIN_HASH_ARRAY_LENGTH = 4
  private const val MAX_HASH_ARRAY_LENGTH = (-0x80000000).ushr(1)

  fun minBufferSize(
    elements: Int,
    loadFactor: Double
  ): Int {
    var length = ceil(elements / loadFactor)
      .toLong()
    if (length == elements.toLong()) {
      length++
    }
    length = max(MIN_HASH_ARRAY_LENGTH.toLong(), nextHighestPowerOfTwo(length))

    if (length > MAX_HASH_ARRAY_LENGTH) {
      throw RuntimeException(tooManyElementsMessage(elements, loadFactor))
    }

    return length.toInt()
  }

  /**
   * These structures hash into a power of two sized array and address slots with an [Int], so the
   * array can't grow past 2^30 slots, which at [loadFactor] is a hard cap on how many elements they
   * can hold. Says so, rather than leaving a caller with an array size it never asked for.
   */
  private fun tooManyElementsMessage(
    elements: Int,
    loadFactor: Double
  ): String {
    return String.format(
      Locale.ROOT,
      "Cannot hold more than %d elements (a hash array of at most %d slots at load factor %f), " +
        "asked to hold %d. This is a limit of Shark's hash structures, not of the heap dump " +
        "format: a heap dump with more objects than that needs a structure that doesn't hash " +
        "object ids, like shark.internal.ObjectIdSet.",
      expandAtCount(MAX_HASH_ARRAY_LENGTH, loadFactor),
      MAX_HASH_ARRAY_LENGTH,
      loadFactor,
      elements
    )
  }

  fun nextHighestPowerOfTwo(input: Long): Long {
    var v = input
    v--
    v = v or (v shr 1)
    v = v or (v shr 2)
    v = v or (v shr 4)
    v = v or (v shr 8)
    v = v or (v shr 16)
    v = v or (v shr 32)
    v++
    return v
  }

  fun expandAtCount(
    arraySize: Int,
    loadFactor: Double
  ): Int {
    return min(arraySize - 1, ceil(arraySize * loadFactor).toInt())
  }

  fun nextBufferSize(
    arraySize: Int,
    elements: Int,
    loadFactor: Double
  ): Int {
    if (arraySize == MAX_HASH_ARRAY_LENGTH) {
      throw RuntimeException(tooManyElementsMessage(elements, loadFactor))
    }

    return arraySize shl 1
  }
}
