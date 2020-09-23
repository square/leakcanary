package shark.internal

import shark.HeapGraph
import shark.HeapObject.HeapInstance

internal class AndroidNativeSizeMapper(private val graph: HeapGraph) {

  /**
   * Returns a map of Object id to native size as tracked by NativeAllocationRegistry$CleanerThunk
   */
  fun mapNativeSizes(): Map<Long, Int> {
    val nativeSizes = mutableMapOf<Long, Int>()
    // Doc from perflib:
    // Native allocations can be identified by looking at instances of
    // libcore.util.NativeAllocationRegistry$CleanerThunk. The "owning" Java object is the
    // "referent" field of the "sun.misc.Cleaner" instance with a hard reference to the
    // CleanerThunk.
    //
    // The size is in the 'size' field of the libcore.util.NativeAllocationRegistry instance
    // that the CleanerThunk has a pointer to. The native pointer is in the 'nativePtr' field of
    // the CleanerThunk. The hprof does not include the native bytes pointed to.
    graph.findClassByName("sun.misc.Cleaner")?.let { cleanerClass ->
      cleanerClass.directInstances.forEach { cleaner ->
        val thunkField = cleaner["sun.misc.Cleaner", "thunk"]
        val thunkId = thunkField?.value?.asNonNullObjectId
        val referentId =
          cleaner["java.lang.ref.Reference", "referent"]?.value?.asNonNullObjectId
        if (thunkId != null && referentId != null) {
          val thunkRecord = thunkField.value.asObject
          if (thunkRecord is HeapInstance && thunkRecord instanceOf "libcore.util.NativeAllocationRegistry\$CleanerThunk") {
            val allocationRegistryIdField =
              thunkRecord["libcore.util.NativeAllocationRegistry\$CleanerThunk", "this\$0"]
            if (allocationRegistryIdField != null && allocationRegistryIdField.value.isNonNullReference) {
              val allocationRegistryRecord = allocationRegistryIdField.value.asObject
              if (allocationRegistryRecord is HeapInstance && allocationRegistryRecord instanceOf "libcore.util.NativeAllocationRegistry") {
                var nativeSize = nativeSizes[referentId] ?: 0
                nativeSize += allocationRegistryRecord["libcore.util.NativeAllocationRegistry", "size"]?.value?.asLong?.toInt()
                    ?: 0
                nativeSizes[referentId] = nativeSize
              }
            }
          }
        }
      }
    }
    return nativeSizes
  }
}