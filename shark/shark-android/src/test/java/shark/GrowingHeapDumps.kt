package shark

import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder

/**
 * [dumpCount] heap dumps of a heap made of [stableEntryCount] entries that stay put and
 * [growingArrayCount] arrays that each hold [growthPerDump] more entries in each dump. Each entry is
 * an instance holding a string, so that walking the heap involves the mix of instances, object
 * arrays and primitive arrays that walking a real heap does.
 *
 * The entries of a growing array each hold their own instance but share their string with the entry
 * at the same index in every other growing array, so that with more than one growing array the heap
 * has a subgraph that all of them reach and none of them retains on its own.
 */
fun growingHeapDumps(
  dumpCount: Int,
  stableEntryCount: Int,
  growthPerDump: Int,
  growingArrayCount: Int = 1
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
      val entry = { index: Int, name: ReferenceHolder ->
        instance(entryClassId, listOf(name, IntHolder(index)))
      }
      val stableEntries = (1..stableEntryCount).map { index ->
        entry(index, string("Stable $index"))
      }
      val sharedNames = (1..dumpIndex * growthPerDump).map { index ->
        string("Growing $index")
      }
      val growingArrays = (1..growingArrayCount).map {
        objectArray(*sharedNames.mapIndexed { index, name -> entry(index, name) }.toTypedArray())
      }
      clazz(
        "Retainer",
        staticFields = listOf("stable" to objectArray(*stableEntries.toTypedArray())) +
          growingArrays.mapIndexed { index, growingArray -> "growing$index" to growingArray }
      )
    }
  }
}
