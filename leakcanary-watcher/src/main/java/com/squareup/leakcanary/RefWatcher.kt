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
package com.squareup.leakcanary

import java.lang.ref.ReferenceQueue
import java.util.HashSet
import java.util.UUID

/**
 * Thread safe by locking on all methods, which is reasonably efficient given how often
 * these methods are accessed.
 */
class RefWatcher constructor(
  private val clock: Clock
) {

  private val retainedKeys = mutableMapOf<String, KeyedWeakReference>()
  private val queue = ReferenceQueue<Any>()
  private val newRefListeners = mutableListOf<() -> Unit>()

  val isEmpty: Boolean
    @Synchronized get() {
      removeWeaklyReachableReferences()
      return retainedKeys.isEmpty()
    }

  val allRetainedKeys: Set<String>
    @Synchronized get() {
      removeWeaklyReachableReferences()
      return HashSet(retainedKeys.keys)
    }

  @Synchronized fun addNewRefListener(listener: () -> Unit) {
    newRefListeners.add(listener)
  }

  @Synchronized fun removeNewRefListener(listener: () -> Unit) {
    newRefListeners.remove(listener)
  }

  /**
   * Identical to [.watch] with an empty string reference name.
   */
  @Synchronized fun watch(watchedReference: Any) {
    watch(watchedReference, "")
  }

  /**
   * Watches the provided references and notifies registered [NewRefListener]s.
   *
   * @param referenceName An logical identifier for the watched object.
   */
  @Synchronized fun watch(
    watchedReference: Any,
    referenceName: String
  ) {
    val key = UUID.randomUUID()
        .toString()
    val watchUptimeMillis = clock.uptimeMillis()
    val reference =
      KeyedWeakReference(watchedReference, key, referenceName, watchUptimeMillis, queue)
    retainedKeys[key] = reference

    newRefListeners.forEach { it() }
  }

  /**
   * LeakCanary will stop watching any references that were passed to [.watch]
   * so far.
   */
  @Synchronized fun clearWatchedReferences() {
    retainedKeys.clear()
  }

  @Synchronized fun hasReferencesOlderThan(durationMillis: Long): Boolean {
    removeWeaklyReachableReferences()
    val now = clock.uptimeMillis()
    var count = 0
    for (reference in retainedKeys.values) {
      if (now - reference.watchUptimeMillis >= durationMillis) {
        count++
      }
    }
    return count > 0
  }

  @Synchronized fun getRetainedKeysOlderThan(durationMillis: Long): Set<String> {
    removeWeaklyReachableReferences()
    val now = clock.uptimeMillis()
    val retainedKeys = HashSet<String>()
    for ((key, value) in this.retainedKeys) {
      if (now - value.watchUptimeMillis >= durationMillis) {
        retainedKeys.add(key)
      }
    }
    return retainedKeys
  }

  @Synchronized fun removeRetainedKeys(keysToRemove: Set<String>) {
    retainedKeys.keys.removeAll(keysToRemove)
  }

  @Synchronized private fun removeWeaklyReachableReferences() {
    // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
    // reachable. This is before finalization or garbage collection has actually happened.
    var ref: KeyedWeakReference?
    do {
      ref = queue.poll() as KeyedWeakReference?
      if (ref != null) {
        retainedKeys.remove(ref.key)
      }
    } while (ref != null)
  }
}
