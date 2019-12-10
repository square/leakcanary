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

import shark.SharkLog
import java.lang.ref.ReferenceQueue
import java.util.UUID
import java.util.concurrent.Executor

/**
 * [ObjectWatcher] can be passed objects to [watch]. It will create [KeyedWeakReference] instances
 * that reference watches objects, and check if those references have been cleared as expected on
 * the [checkRetainedExecutor] executor. If not, these objects are considered retained and
 * [ObjectWatcher] will then notify the [onObjectRetainedListener] on that executor thread.
 *
 * [checkRetainedExecutor] is expected to run its tasks on a background thread, with a significant
 * to give the GC the opportunity to identify weakly reachable objects.
 *
 * [ObjectWatcher] is thread safe.
 */
// Thread safe by locking on all methods, which is reasonably efficient given how often
// these methods are accessed.
class ObjectWatcher constructor(
  private val clock: Clock,
  private val checkRetainedExecutor: Executor,
  /**
   * Calls to [watch] will be ignored when [isEnabled] returns false
   */
  private val isEnabled: () -> Boolean = { true }
) {

  private val onObjectRetainedListeners = mutableSetOf<OnObjectRetainedListener>()

  /**
   * References passed to [watch].
   */
  private val watchedObjects = mutableMapOf<String, KeyedWeakReference>()

  private val queue = ReferenceQueue<Any>()

  /**
   * Returns true if there are watched objects that aren't weakly reachable, and
   * have been watched for long enough to be considered retained.
   */
  val hasRetainedObjects: Boolean
    @Synchronized get() {
      removeWeaklyReachableObjects()
      return watchedObjects.any { it.value.retainedUptimeMillis != -1L }
    }

  /**
   * Returns the number of retained objects, ie the number of watched objects that aren't weakly
   * reachable, and have been watched for long enough to be considered retained.
   */
  val retainedObjectCount: Int
    @Synchronized get() {
      removeWeaklyReachableObjects()
      return watchedObjects.count { it.value.retainedUptimeMillis != -1L }
    }

  /**
   * Returns true if there are watched objects that aren't weakly reachable, even
   * if they haven't been watched for long enough to be considered retained.
   */
  val hasWatchedObjects: Boolean
    @Synchronized get() {
      removeWeaklyReachableObjects()
      return watchedObjects.isNotEmpty()
    }

  /**
   * Returns the objects that are currently considered retained. Useful for logging purposes.
   * Be careful with those objects and release them ASAP as you may creating longer lived leaks
   * then the one that are already there.
   */
  val retainedObjects: List<Any>
    @Synchronized get() {
      removeWeaklyReachableObjects()
      val instances = mutableListOf<Any>()
      for (weakReference in watchedObjects.values) {
        if (weakReference.retainedUptimeMillis != -1L) {
          val instance = weakReference.get()
          if (instance != null) {
            instances.add(instance)
          }
        }
      }
      return instances
    }

  @Synchronized fun addOnObjectRetainedListener(listener: OnObjectRetainedListener) {
    onObjectRetainedListeners.add(listener)
  }

  @Synchronized fun removeOnObjectRetainedListener(listener: OnObjectRetainedListener) {
    onObjectRetainedListeners.remove(listener)
  }

  /**
   * Identical to [watch] with an empty string reference name.
   */
  @Deprecated(
      "Add description parameter explaining why an object is watched to help understand leak traces.",
      replaceWith = ReplaceWith(
          "watch(watchedObject, \"Explain why this object should be garbage collected soon\")"
      )
  )
  @Synchronized fun watch(watchedObject: Any) {
    watch(watchedObject, "")
  }

  /**
   * Watches the provided [watchedObject].
   *
   * @param description Describes why the object is watched.
   */
  @Synchronized fun watch(
    watchedObject: Any,
    description: String
  ) {
    if (!isEnabled()) {
      return
    }
    removeWeaklyReachableObjects()
    val key = UUID.randomUUID()
        .toString()
    val watchUptimeMillis = clock.uptimeMillis()
    val reference =
      KeyedWeakReference(watchedObject, key, description, watchUptimeMillis, queue)
    SharkLog.d {
      "Watching " +
          (if (watchedObject is Class<*>) watchedObject.toString() else "instance of ${watchedObject.javaClass.name}") +
          (if (description.isNotEmpty()) " ($description)" else "") +
          " with key $key"
    }

    watchedObjects[key] = reference
    checkRetainedExecutor.execute {
      moveToRetained(key)
    }
  }

  /**
   * Clears all [KeyedWeakReference] that were created before [heapDumpUptimeMillis] (based on
   * [clock] [Clock.uptimeMillis])
   */
  @Synchronized fun clearObjectsWatchedBefore(heapDumpUptimeMillis: Long) {
    val weakRefsToRemove =
      watchedObjects.filter { it.value.watchUptimeMillis <= heapDumpUptimeMillis }
    weakRefsToRemove.values.forEach { it.clear() }
    watchedObjects.keys.removeAll(weakRefsToRemove.keys)
  }

  /**
   * Clears all [KeyedWeakReference]
   */
  @Synchronized fun clearWatchedObjects() {
    watchedObjects.values.forEach { it.clear() }
    watchedObjects.clear()
  }

  @Synchronized private fun moveToRetained(key: String) {
    removeWeaklyReachableObjects()
    val retainedRef = watchedObjects[key]
    if (retainedRef != null) {
      retainedRef.retainedUptimeMillis = clock.uptimeMillis()
      onObjectRetainedListeners.forEach { it.onObjectRetained() }
    }
  }

  private fun removeWeaklyReachableObjects() {
    // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
    // reachable. This is before finalization or garbage collection has actually happened.
    var ref: KeyedWeakReference?
    do {
      ref = queue.poll() as KeyedWeakReference?
      if (ref != null) {
        watchedObjects.remove(ref.key)
      }
    } while (ref != null)
  }
}
