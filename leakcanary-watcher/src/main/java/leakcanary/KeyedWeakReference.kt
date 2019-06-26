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

@Suppress("unused")
class KeyedWeakReference(
  referent: Any,
  val key: String,
  val name: String,
  val watchUptimeMillis: Long,
  referenceQueue: ReferenceQueue<Any>
) : WeakReference<Any>(
    referent, referenceQueue
) {
  val className: String = referent.javaClass.name

  /**
   * Compared against [heapDumpUptimeMillis] so that the Hprof Parser knows only to look at
   * instances that were moved to retained, then used to remove weak references post heap dump.
   **/
  @Volatile
  var retainedUptimeMillis = -1L

  companion object {
    @Volatile
    @JvmStatic var heapDumpUptimeMillis = 0L
  }

}
