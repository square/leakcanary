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
package leakcanary

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import leakcanary.KeyedWeakReference.Companion.heapDumpUptimeMillis

/**
 * A weak reference used by [ReferenceQueueRetainedObjectTracker] to determine which objects become
 * weakly reachable and which don't. [ReferenceQueueRetainedObjectTracker] uses [key] to keep track
 * of [KeyedWeakReference] instances that haven't made it into the associated [ReferenceQueue] yet.
 *
 * [heapDumpUptimeMillis] should be set with the current time from [UptimeClock.uptime] right
 * before dumping the heap, so that we can later determine how long an object was retained.
 */
class KeyedWeakReference(
  referent: Any,
  val key: String,
  val description: String,
  val watchUptimeMillis: Long,
  referenceQueue: ReferenceQueue<Any>
) : WeakReference<Any>(
  referent, referenceQueue
) {
  /**
   * Time at which the associated object ([referent]) was considered retained, or -1 if it hasn't
   * been yet.
   */
  @Volatile
  var retainedUptimeMillis = -1L

  val retained: Boolean
    get() = retainedUptimeMillis != -1L

  override fun clear() {
    super.clear()
    retainedUptimeMillis = -1L
  }

  override fun get(): Any? {
    error("Calling KeyedWeakReference.get() is a mistake as it revives the reference")
  }

  /**
   * Same as [WeakReference.get] but does not trigger an intentional crash.
   *
   * Calling this method will end up creating local references to the objects, preventing them from
   * becoming weakly reachable, and creating a leak. If you need to check for identity equality, use
   * Reference.refersTo instead.
   */
  fun getAndLeakReferent(): Any? {
    return super.get()
  }

  companion object {
    @Volatile
    @JvmStatic var heapDumpUptimeMillis = 0L
  }
}
