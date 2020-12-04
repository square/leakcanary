/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package leakcanary.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class FutureResult<T> {

  private val resultHolder: AtomicReference<T> = AtomicReference()
  private val latch: CountDownLatch = CountDownLatch(1)

  fun wait(
    timeout: Long,
    unit: TimeUnit
  ): Boolean {
    try {
      return latch.await(timeout, unit)
    } catch (e: InterruptedException) {
      throw RuntimeException("Did not expect thread to be interrupted", e)
    }
  }

  fun get(): T {
    if (latch.count > 0) {
      throw IllegalStateException("Call wait() and check its result")
    }
    return resultHolder.get()
  }

  fun set(result: T) {
    resultHolder.set(result)
    latch.countDown()
  }
}
