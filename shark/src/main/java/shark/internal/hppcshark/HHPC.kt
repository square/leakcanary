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

package shark.internal.hppcshark

import java.util.Locale

/**
 * Code from https://github.com/carrotsearch/hppc copy pasted, inlined and converted to Kotlin.
 */
internal object HHPC {

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
    var length = Math.ceil(elements / loadFactor)
        .toLong()
    if (length == elements.toLong()) {
      length++
    }
    length = Math.max(
        MIN_HASH_ARRAY_LENGTH.toLong(),
        nextHighestPowerOfTwo(length)
    )

    if (length > MAX_HASH_ARRAY_LENGTH) {
      throw RuntimeException(
          String.format(
              Locale.ROOT,
              "Maximum array size exceeded for this load factor (elements: %d, load factor: %f)",
              elements,
              loadFactor
          )
      )
    }

    return length.toInt()
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
    return Math.min(arraySize - 1, Math.ceil(arraySize * loadFactor).toInt())
  }

  fun nextBufferSize(
    arraySize: Int,
    elements: Int,
    loadFactor: Double
  ): Int {
    if (arraySize == MAX_HASH_ARRAY_LENGTH) {
      throw RuntimeException(
          String.format(
              Locale.ROOT,
              "Maximum array size exceeded for this load factor (elements: %d, load factor: %f)",
              elements,
              loadFactor
          )
      )
    }

    return arraySize shl 1
  }

}