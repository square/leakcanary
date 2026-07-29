package shark

import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder

/**
 * [dumpCount] heap dumps of a heap made of [stableEntryCount] entries that stay put and an array
 * that holds [growthPerDump] more entries in each dump. Each entry is an instance holding a string,
 * so that walking the heap involves the mix of instances, object arrays and primitive arrays that
 * walking a real heap does.
 */
fun growingHeapDumps(
  dumpCount: Int,
  stableEntryCount: Int,
  growthPerDump: Int
): List<DualSourceProvider> {
  return (1..dumpCount).map { dumpIndex ->
    dump {
      val entryClassId = clazz(
        "Entry",
        fields = listOf(
          "name" to ReferenceHolder::class,
          "index" to IntHolder::class
        )
      )
      val entry = { index: Int ->
        instance(entryClassId, listOf(string("Entry $index"), IntHolder(index)))
      }
      val stableEntries = (1..stableEntryCount).map { entry(it) }
      val growingEntries = (1..dumpIndex * growthPerDump).map { entry(it) }
      clazz(
        "Retainer",
        staticFields = listOf(
          "stable" to objectArray(*stableEntries.toTypedArray()),
          "growing" to objectArray(*growingEntries.toTypedArray())
        )
      )
    }
  }
}
