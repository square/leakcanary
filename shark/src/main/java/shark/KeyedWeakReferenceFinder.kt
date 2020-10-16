package shark

import shark.ObjectInspectors.KEYED_WEAK_REFERENCE
import shark.internal.KeyedWeakReferenceMirror

/**
 * Finds all objects tracked by a KeyedWeakReference, ie all objects that were passed to
 * ObjectWatcher.watch.
 */
object KeyedWeakReferenceFinder : LeakingObjectFinder {

  override fun findLeakingObjectIds(graph: HeapGraph): Set<Long> =
    findKeyedWeakReferences(graph).map { it.referent.value }
        .toSet()

  fun heapDumpUptimeMillis(graph: HeapGraph): Long? {
    return graph.context.getOrPut("heapDumpUptimeMillis") {
      val keyedWeakReferenceClass = graph.findClassByName("leakcanary.KeyedWeakReference")
      val heapDumpUptimeMillis = if (keyedWeakReferenceClass == null) {
        null
      } else {
        keyedWeakReferenceClass["heapDumpUptimeMillis"]?.value?.asLong
      }
      if (heapDumpUptimeMillis == null) {
        SharkLog.d {
          "leakcanary.KeyedWeakReference.heapDumpUptimeMillis field not found"
        }
      }
      heapDumpUptimeMillis
    }
  }

  internal fun findKeyedWeakReferences(graph: HeapGraph): List<KeyedWeakReferenceMirror> {
    return graph.context.getOrPut(KEYED_WEAK_REFERENCE.name) {
      val keyedWeakReferenceClass = graph.findClassByName("leakcanary.KeyedWeakReference")

      val keyedWeakReferenceClassId = keyedWeakReferenceClass?.objectId ?: 0
      val legacyKeyedWeakReferenceClassId =
        graph.findClassByName("com.squareup.leakcanary.KeyedWeakReference")?.objectId ?: 0

      val heapDumpUptimeMillis = heapDumpUptimeMillis(graph)

      val addedToContext: List<KeyedWeakReferenceMirror> = graph.instances
          .filter { instance ->
            instance.instanceClassId == keyedWeakReferenceClassId || instance.instanceClassId == legacyKeyedWeakReferenceClassId
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