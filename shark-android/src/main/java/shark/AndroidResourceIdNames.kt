package shark

import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump

class AndroidResourceIdNames private constructor(
  private val resourceIds: IntArray,
  private val names: Array<String>
) {

  operator fun get(id: Int): String? {
    val indexOfId = resourceIds.binarySearch(id)
    return if (indexOfId >= 0) {
      names[indexOfId]
    } else {
      null
    }
  }

  companion object {

    internal const val FIRST_APP_RESOURCE_ID = 0x7F010000
    internal const val RESOURCE_ID_TYPE_ITERATOR = 0x00010000

    @Volatile
    @JvmStatic
    private var holderField: AndroidResourceIdNames? = null

    /**
     * @param getResourceTypeName a function that delegates to Android
     * Resources.getResourceTypeName but returns null when the name isn't found instead of
     * throwing an exception.
     *
     * @param getResourceEntryName a function that delegates to Android
     * Resources.getResourceEntryName but returns null when the name isn't found instead of
     * throwing an exception.
     */
    @Synchronized fun saveToMemory(
      getResourceTypeName: (Int) -> String?,
      getResourceEntryName: (Int) -> String?
    ) {
      if (holderField != null) {
        return
      }

      // This is based on https://jebware.com/blog/?p=600 which itself is based on
      // https://stackoverflow.com/a/6646113/703646

      val idToNamePairs = mutableListOf<Pair<Int, String>>()
      findIdTypeResourceIdStart(getResourceTypeName)?.let { idTypeResourceIdStart ->
        var resourceId = idTypeResourceIdStart
        while (true) {
          val entry = getResourceEntryName(resourceId) ?: break
          idToNamePairs += resourceId to entry
          resourceId++
        }
      }
      val resourceIds = idToNamePairs.map { it.first }
          .toIntArray()
      val names = idToNamePairs.map { it.second }
          .toTypedArray()
      holderField = AndroidResourceIdNames(resourceIds, names)
    }

    private fun findIdTypeResourceIdStart(getResourceTypeName: (Int) -> String?): Int? {
      var resourceTypeId = FIRST_APP_RESOURCE_ID
      while (true) {
        when (getResourceTypeName(resourceTypeId)) {
          null -> return null
          "id" -> return resourceTypeId
          else -> resourceTypeId += RESOURCE_ID_TYPE_ITERATOR
        }
      }
    }

    fun readFromHeap(graph: HeapGraph): AndroidResourceIdNames? {
      return graph.context.getOrPut(AndroidResourceIdNames::class.java.name) {
        val className = AndroidResourceIdNames::class.java.name
        val holderClass = graph.findClassByName(className)
        holderClass?.let {
          val holderField = holderClass["holderField"]!!
          holderField.valueAsInstance?.let { instance ->
            val resourceIds =
              (instance[className, "resourceIds"]!!.valueAsPrimitiveArray!!.readRecord() as IntArrayDump).array
            val names = instance[className, "names"]!!.valueAsObjectArray!!.readElements()
                .map { it.readAsJavaString()!! }
                .toList()
                .toTypedArray()
            AndroidResourceIdNames(resourceIds, names)
          }
        }
      }
    }

    internal fun resetForTests() {
      holderField = null
    }
  }

}