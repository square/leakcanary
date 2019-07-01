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
import java.util.UUID
import java.util.concurrent.Executor

/**
 * Thread safe by locking on all methods, which is reasonably efficient given how often
 * these methods are accessed.
 */
class RefWatcher constructor(
  private val clock: Clock,
  private val checkRetainedExecutor: Executor,
  private val onInstanceRetained: () -> Unit,
  /**
   * Calls to [watch] will be ignored when [isEnabled] returns false
   */
  private val isEnabled: () -> Boolean = { true }
) {

  /**
   * References passed to [watch].
   */
  private val watchedInstances = mutableMapOf<String, KeyedWeakReference>()

  private val queue = ReferenceQueue<Any>()

  /**
   * Returns true if there are watched instances that aren't weakly reachable, and
   * have been watched for long enough to be considered retained.
   */
  val hasRetainedInstances: Boolean
    @Synchronized get() {
      removeWeaklyReachableInstances()
      return watchedInstances.any { it.value.retainedUptimeMillis != -1L }
    }

  val retainedInstanceCount: Int
    @Synchronized get() {
      removeWeaklyReachableInstances()
      return watchedInstances.count { it.value.retainedUptimeMillis != -1L }
    }

  /**
   * Returns true if there are watched instances that aren't weakly reachable, even
   * if they haven't been watched for long enough to be considered retained.
   */
  val hasWatchedInstances: Boolean
    @Synchronized get() {
      removeWeaklyReachableInstances()
      return watchedInstances.isNotEmpty()
    }

  /**
   * Returns the instances that are currently considered retained. Useful for logging purposes.
   * Be careful with those instances and release them ASAP as you may creating longer lived leaks
   * then the one that are already there.
   */
  val retainedInstances: List<Any>
    @Synchronized get() {
      removeWeaklyReachableInstances()
      val instances = mutableListOf<Any>()
      for (weakReference in watchedInstances.values) {
        if (weakReference.retainedUptimeMillis != -1L) {
          val instance = weakReference.get()
          if (instance != null) {
            instances.add(instance)
          }
        }
      }
      return instances
    }

  /**
   * Identical to [.watch] with an empty string reference name.
   */
  @Synchronized fun watch(watchedInstance: Any) {
    watch(watchedInstance, "")
  }

  /**
   * Watches the provided instances.
   *
   * @param name A logical identifier for the watched object.
   */
  @Synchronized fun watch(
    watchedInstance: Any,
    name: String
  ) {
    if (!isEnabled()) {
      return
    }
    removeWeaklyReachableInstances()
    val key = UUID.randomUUID()
        .toString()
    val watchUptimeMillis = clock.uptimeMillis()
    val reference =
      KeyedWeakReference(watchedInstance, key, name, watchUptimeMillis, queue)
    if (name != "") {
      CanaryLog.d(
          "Watching instance of %s named %s with key %s", reference.className,
          name, key
      )
    } else {
      CanaryLog.d(
          "Watching instance of %s with key %s", reference.className, key
      )
    }

    watchedInstances[key] = reference
    checkRetainedExecutor.execute {
      moveToRetained(key)
    }
  }

  @Synchronized private fun moveToRetained(key: String) {
    removeWeaklyReachableInstances()
    val retainedRef = watchedInstances[key]
    if (retainedRef != null) {
      retainedRef.retainedUptimeMillis = clock.uptimeMillis()
      onInstanceRetained()
    }
  }

  @Synchronized fun removeInstancesRetainedBeforeHeapDump(heapDumpUptimeMillis: Long) {
    val retainedBeforeHeapdump =
      watchedInstances.filter { it.value.retainedUptimeMillis in 0..heapDumpUptimeMillis }
          .keys
    watchedInstances.keys.removeAll(retainedBeforeHeapdump)
  }

  @Synchronized fun clearWatchedInstances() {
    watchedInstances.clear()
  }

  private fun removeWeaklyReachableInstances() {
    // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
    // reachable. This is before finalization or garbage collection has actually happened.
    var ref: KeyedWeakReference?
    do {
      ref = queue.poll() as KeyedWeakReference?
      if (ref != null) {
        watchedInstances.remove(ref.key)
      }
    } while (ref != null)
  }
}
