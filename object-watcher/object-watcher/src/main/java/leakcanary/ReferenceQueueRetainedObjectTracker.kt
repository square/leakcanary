package leakcanary

import java.lang.ref.ReferenceQueue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import leakcanary.TriggeredDeletableObjectReporter.RetainTrigger
import shark.SharkLog

/**
 * [ReferenceQueueRetainedObjectTracker] can be passed objects to [expectDeletionOnTriggerFor].
 * It will create [KeyedWeakReference] instances that reference tracked objects, and check if those
 * references have been cleared as expected. If not, these
 * objects are considered retained and [ReferenceQueueRetainedObjectTracker] will then notify
 * registered [OnObjectRetainedListener]s.
 * [ReferenceQueueRetainedObjectTracker] is thread safe.
 */
class ReferenceQueueRetainedObjectTracker constructor(
  private val clock: UptimeClock,
  private val onObjectRetainedListener: OnObjectRetainedListener
) : RetainedObjectTracker, TriggeredDeletableObjectReporter {

  /**
   * References passed to [expectDeletionOnTriggerFor].
   */
  private val watchedObjects: MutableMap<String, KeyedWeakReference> = ConcurrentHashMap()

  private val queue = ReferenceQueue<Any>()

  /**
   * List of [KeyedWeakReference] that have not been enqueued in the reference queue yet, which
   * means their referent is most likely still strongly reachable.
   *
   * DO NOT CALL [java.lang.ref.Reference.get] on the returned references, otherwise you will
   * end up creating local references to the objects, preventing them from be becoming weakly
   * reachable, and creating a leak. If you need to check for identity equality, use
   * Reference.refersTo instead.
   */
  val trackedWeakReferences: List<KeyedWeakReference>
    get() {
      removeWeaklyReachableObjects()
      return watchedObjects.values.toList()
    }

  /**
   * Subset of [trackedWeakReferences] that have been marked as retained.
   *
   * DO NOT CALL [java.lang.ref.Reference.get] on the returned references, otherwise you will
   * end up creating local references to the objects, preventing them from becoming weakly
   * reachable, and creating a leak. If you need to check for identity equality, use
   * Reference.refersTo instead.
   */
  val retainedWeakReferences: List<KeyedWeakReference>
    get() {
      removeWeaklyReachableObjects()
      return watchedObjects.values.filter { it.retained }.toList()
    }

  override val hasRetainedObjects: Boolean
    get() {
      removeWeaklyReachableObjects()
      return watchedObjects.any { it.value.retained }
    }

  override val retainedObjectCount: Int
    get() {
      removeWeaklyReachableObjects()
      return watchedObjects.count { it.value.retained }
    }

  override val hasTrackedObjects: Boolean
    get() {
      removeWeaklyReachableObjects()
      return watchedObjects.isNotEmpty()
    }

  override val trackedObjectCount: Int
    get() {
      removeWeaklyReachableObjects()
      return watchedObjects.size
    }

  override fun expectDeletionOnTriggerFor(
    target: Any,
    reason: String
  ): RetainTrigger {
    removeWeaklyReachableObjects()
    val key = UUID.randomUUID()
      .toString()
    val watchUptime = clock.uptime()
    val reference =
      KeyedWeakReference(target, key, reason, watchUptime.inWholeMilliseconds, queue)
    SharkLog.d {
      "Watching " +
        (if (target is Class<*>) target.toString() else "instance of ${target.javaClass.name}") +
        (if (reason.isNotEmpty()) " ($reason)" else "") +
        " with key $key"
    }

    watchedObjects[key] = reference
    return object : RetainTrigger {
      override val isStronglyReachable: Boolean
        get() {
          removeWeaklyReachableObjects()
          val weakRef = watchedObjects[key]
          return weakRef != null
        }

      override val isRetained: Boolean
        get() {
          removeWeaklyReachableObjects()
          val weakRef = watchedObjects[key]
          return weakRef?.retained ?: false
        }

      override fun markRetainedIfStronglyReachable() {
        moveToRetained(key)
      }
    }
  }

  override fun clearObjectsTrackedBefore(uptime: Duration) {
    val weakRefsToRemove =
      watchedObjects.filter { it.value.watchUptimeMillis <= uptime.inWholeMilliseconds }
    weakRefsToRemove.values.forEach { it.clear() }
    watchedObjects.keys.removeAll(weakRefsToRemove.keys)
  }

  /**
   * Clears all [KeyedWeakReference]
   */
  override fun clearAllObjectsTracked() {
    watchedObjects.values.forEach { it.clear() }
    watchedObjects.clear()
  }

  private fun moveToRetained(key: String) {
    removeWeaklyReachableObjects()
    val retainedRef = watchedObjects[key]
    if (retainedRef != null) {
      retainedRef.retainedUptimeMillis = clock.uptime().inWholeMilliseconds
      onObjectRetainedListener.onObjectRetained()
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
