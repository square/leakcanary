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
package leaksentry

import java.lang.ref.ReferenceQueue
import java.util.HashSet
import java.util.UUID
import java.util.concurrent.Executor

/**
 * Thread safe by locking on all methods, which is reasonably efficient given how often
 * these methods are accessed.
 */
class RefWatcher constructor(
  private val clock: Clock,
  private val checkRetainedExecutor: Executor,
  private val onReferenceRetained: () -> Unit
) {

  /**
   * References passed to [watch] that haven't made it to [retainedReferences] yet.
   */
  private val watchedReferences = mutableMapOf<String, KeyedWeakReference>()
  /**
   * References passed to [watch] that we have determined to be retained longer than they should
   * have been.
   */
  private val retainedReferences = mutableMapOf<String, KeyedWeakReference>()
  private val queue = ReferenceQueue<Any>()

  val hasRetainedReferences: Boolean
    @Synchronized get() {
      removeWeaklyReachableReferences()
      return !retainedReferences.isEmpty()
    }

  val hasWatchedReferences: Boolean
    @Synchronized get() {
      removeWeaklyReachableReferences()
      return !retainedReferences.isEmpty() || !watchedReferences.isEmpty()
    }

  val retainedKeys: Set<String>
    @Synchronized get() {
      removeWeaklyReachableReferences()
      return HashSet(retainedReferences.keys)
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
    removeWeaklyReachableReferences()
    val key = UUID.randomUUID()
        .toString()
    val watchUptimeMillis = clock.uptimeMillis()
    val reference =
      KeyedWeakReference(watchedReference, key, referenceName, watchUptimeMillis, queue)
    watchedReferences[key] = reference
    checkRetainedExecutor.execute {
      moveToRetained(key)
    }
  }

  @Synchronized private fun moveToRetained(key: String) {
    removeWeaklyReachableReferences()
    val retainedRef = watchedReferences.remove(key)
    if (retainedRef != null) {
      retainedReferences[key] = retainedRef
      onReferenceRetained()
    }
  }

  @Synchronized fun removeRetainedKeys(keysToRemove: Set<String>) {
    retainedReferences.keys.removeAll(keysToRemove)
  }

  @Synchronized fun clearWatchedReferences() {
    watchedReferences.clear()
    retainedReferences.clear()
  }

  @Synchronized private fun removeWeaklyReachableReferences() {
    // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
    // reachable. This is before finalization or garbage collection has actually happened.
    var ref: KeyedWeakReference?
    do {
      ref = queue.poll() as KeyedWeakReference?
      if (ref != null) {
        val removedRef = watchedReferences.remove(ref.key)
        if (removedRef == null) {
          retainedReferences.remove(ref.key)
        }
      }
    } while (ref != null)
  }
}
