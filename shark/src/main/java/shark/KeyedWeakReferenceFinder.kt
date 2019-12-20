package shark

import shark.ObjectInspectors.KEYED_WEAK_REFERENCE
import shark.internal.KeyedWeakReferenceMirror

/**
 * Finds all objects tracked by a KeyedWeakReference, ie all objects that were passed to
 * ObjectWatcher.watch.
 */
object KeyedWeakReferenceFinder : LeakingObjectFinder {

  override fun findLeakingObjectIds(graph: HeapGraph): Set<Long> =
    findKeyedWeakReferences(graph).map { it.referent.value }.toSet()

  internal fun findKeyedWeakReferences(graph: HeapGraph): List<KeyedWeakReferenceMirror> {
    return graph.context.getOrPut(KEYED_WEAK_REFERENCE.name) {
      val keyedWeakReferenceClass = graph.findClassByName("leakcanary.KeyedWeakReference")

      val heapDumpUptimeMillis = if (keyedWeakReferenceClass == null) {
        null
      } else {
        keyedWeakReferenceClass["heapDumpUptimeMillis"]?.value?.asLong
      }

      if (heapDumpUptimeMillis == null) {
        SharkLog.d {
          "leakcanary.KeyedWeakReference.heapDumpUptimeMillis field not found, " +
              "this must be a heap dump from an older version of LeakCanary."
        }
      }

      val addedToContext: List<KeyedWeakReferenceMirror> = graph.instances
          .filter { instance ->
            val className = instance.instanceClassName
            className == "leakcanary.KeyedWeakReference" || className == "com.squareup.leakcanary.KeyedWeakReference"
          }
          .map {
            KeyedWeakReferenceMirror.fromInstance(
                it, heapDumpUptimeMillis
            )
          }
          .filter { it.hasReferent }
          .toList()
      graph.context[KEYED_WEAK_REFERENCE.name] = addedToContext
      addedToContext
    }
  }
}