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

import leakcanary.KeyedWeakReference.Companion.heapDumpUptimeMillis
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * A weak reference used by [ObjectWatcher] to determine which objects become weakly reachable
 * and which don't. [ObjectWatcher] uses [key] to keep track of [KeyedWeakReference] instances that
 * haven't made it into the associated [ReferenceQueue] yet.
 *
 * [heapDumpUptimeMillis] should be set with the current time from [Clock.uptimeMillis] right
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

  companion object {
    @Volatile
    @JvmStatic var heapDumpUptimeMillis = 0L
  }

}
