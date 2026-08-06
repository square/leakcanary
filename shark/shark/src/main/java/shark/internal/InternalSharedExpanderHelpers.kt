package shark.internal

import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader
import shark.HeapMapEntry
import shark.HeapObject.HeapInstance
import shark.HeapValue
import shark.Reference
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.ARRAY_ENTRY

internal class InternalSharedHashMapReferenceReader(
  private val className: String,
  private val tableFieldName: String,
  private val nodeClassName: String,
  private val nodeNextFieldName: String,
  private val nodeKeyFieldName: String,
  private val nodeValueFieldName: String,
  private val keyName: String,
  private val keysOnly: Boolean,
  private val matches: (HeapInstance) -> Boolean,
  private val declaringClassId: (HeapInstance) -> (Long)
) : VirtualInstanceReferenceReader {
  override fun matches(instance: HeapInstance): Boolean {
    return matches.invoke(instance)
  }

  override val readsCutSet = true

  /**
   * The entries [source] holds, each as the node holding it and the key and value it maps — which is
   * what [read] turns into references, and what [shark.MapEntryReader] hands to a caller that needs the
   * node itself rather than a reference that skips it.
   *
   * Empty for a map that has never been written to: a map allocates its table on the first put.
   */
  fun readEntries(source: HeapInstance): Sequence<HeapMapEntry> {
    return entryNodes(source).map { node ->
      HeapMapEntry(
        instance = node,
        key = node[nodeClassName, nodeKeyFieldName]!!.value,
        value = node[nodeClassName, nodeValueFieldName]!!.value
      )
    }
  }

  /** Every node of [source]'s table, bucket by bucket and down the chain each bucket starts. */
  private fun entryNodes(source: HeapInstance): Sequence<HeapInstance> {
    val table = source[className, tableFieldName]!!.valueAsObjectArray ?: return emptySequence()
    return table.readElements().mapNotNull { entryRef ->
      if (entryRef.isNonNullReference) {
        val entry = entryRef.asObject!!.asInstance!!
        generateSequence(entry) { node ->
          node[nodeClassName, nodeNextFieldName]!!.valueAsInstance
        }
      } else {
        null
      }
    }.flatten()
  }

  override fun read(source: HeapInstance): Sequence<Reference> {
    val declaringClassId = declaringClassId(source)

    val createKeyRef: (HeapValue) -> Reference? = { key ->
      if (key.isNonNullReference) {
        Reference(
          valueObjectId = key.asObjectId!!,
          isLowPriority = false,
          lazyDetailsResolver = {
            LazyDetails(
              // All entries are represented by the same key name, e.g. "key()"
              name = keyName,
              locationClassObjectId = declaringClassId,
              locationType = ARRAY_ENTRY,
              isVirtual = true,
              matchedLibraryLeak = null
            )
          }
        )
      } else null
    }

    return if (keysOnly) {
      entryNodes(source).mapNotNull { node ->
        val key = node[nodeClassName, nodeKeyFieldName]!!.value
        createKeyRef(key)
      }
    } else {
      readEntries(source).flatMap { entry ->
        val key = entry.key
        val keyRef = createKeyRef(key)
        val value = entry.value
        val valueRef = if (value.isNonNullReference) {
          Reference(
            valueObjectId = value.asObjectId!!,
            isLowPriority = false,
            lazyDetailsResolver = {
              val keyAsString = key.asObject?.asInstance?.readAsJavaString()?.let { "\"$it\"" }
              val keyAsName =
                keyAsString ?: key.asObject?.toString() ?: "null"
              LazyDetails(
                name = keyAsName,
                locationClassObjectId = declaringClassId,
                locationType = ARRAY_ENTRY,
                isVirtual = true,
                matchedLibraryLeak = null
              )
            }
          )
        } else null
        if (keyRef != null && valueRef != null) {
          sequenceOf(keyRef, valueRef)
        } else if (keyRef != null) {
          sequenceOf(keyRef)
        } else if (valueRef != null) {
          sequenceOf(valueRef)
        } else {
          emptySequence()
        }
      }
    }
  }
}

internal class InternalSharedWeakHashMapReferenceReader(
  private val classObjectId: Long,
  private val tableFieldName: String,
  private val isEntryWithNullKey: (HeapInstance) -> Boolean,
) : VirtualInstanceReferenceReader {
  override fun matches(instance: HeapInstance): Boolean {
    return instance.instanceClassId == classObjectId
  }

  override val readsCutSet = true

  override fun read(source: HeapInstance): Sequence<Reference> {
    val table = source["java.util.WeakHashMap", tableFieldName]!!.valueAsObjectArray
    return if (table != null) {
      val entries = table.readElements().mapNotNull { entryRef ->
        if (entryRef.isNonNullReference) {
          val entry = entryRef.asObject!!.asInstance!!
          generateSequence(entry) { node ->
            node["java.util.WeakHashMap\$Entry", "next"]!!.valueAsInstance
          }
        } else {
          null
        }
      }.flatten()

      val declaringClassId = source.instanceClassId

      entries.mapNotNull { entry ->
        val key = if (isEntryWithNullKey(entry)) {
          null
        } else {
          entry["java.lang.ref.Reference", "referent"]!!.value
        }
        if (key?.isNullReference == true) {
          return@mapNotNull null // cleared key
        }
        val value = entry["java.util.WeakHashMap\$Entry", "value"]!!.value
        if (value.isNonNullReference) {
          Reference(
            valueObjectId = value.asObjectId!!,
            isLowPriority = false,
            lazyDetailsResolver = {
              val keyAsString = key?.asObject?.asInstance?.readAsJavaString()?.let { "\"$it\"" }
              val keyAsName = keyAsString ?: key?.asObject?.toString() ?: "null"
              LazyDetails(
                name = keyAsName,
                locationClassObjectId = declaringClassId,
                locationType = ARRAY_ENTRY,
                isVirtual = true,
                matchedLibraryLeak = null
              )
            }
          )
        } else null
      }
    } else {
      emptySequence()
    }
  }
}

internal class InternalSharedArrayListReferenceReader(
  private val className: String,
  private val classObjectId: Long,
  private val elementArrayName: String,
  private val sizeFieldName: String?
) : VirtualInstanceReferenceReader {

  override fun matches(instance: HeapInstance): Boolean {
    return instance.instanceClassId == classObjectId
  }

  override val readsCutSet = true

  override fun read(source: HeapInstance): Sequence<Reference> {
    val instanceClassId = source.instanceClassId
    val elementFieldRef =
      source[className, elementArrayName]!!.valueAsObjectArray ?: return emptySequence()

    val elements = if (sizeFieldName != null) {
      val size = source[className, sizeFieldName]!!.value.asInt!!
      elementFieldRef.readElements().take(size)
    } else {
      elementFieldRef.readElements()
    }
    return elements.withIndex()
      .mapNotNull { (index, elementValue) ->
        if (elementValue.isNonNullReference) {
          Reference(
            valueObjectId = elementValue.asObjectId!!,
            isLowPriority = false,
            lazyDetailsResolver = {
              LazyDetails(
                name = "$index",
                locationClassObjectId = instanceClassId,
                locationType = ARRAY_ENTRY,
                isVirtual = true,
                matchedLibraryLeak = null
              )
            }
          )
        } else {
          null
        }
      }
  }
}

internal class InternalSharedLinkedListReferenceReader(
  private val classObjectId: Long,
  private val headFieldName: String,
  private val nodeClassName: String,
  private val nodeNextFieldName: String,
  private val nodeElementFieldName: String
) : VirtualInstanceReferenceReader {

  override fun matches(instance: HeapInstance): Boolean {
    return instance.instanceClassId == classObjectId
  }

  override val readsCutSet = true

  override fun read(source: HeapInstance): Sequence<Reference> {
    val instanceClassId = source.instanceClassId
    // head may be null, in that case we generate an empty sequence.
    val firstNode = source["java.util.LinkedList", headFieldName]!!.valueAsInstance
    val visitedNodes = mutableSetOf<Long>()
    if (firstNode != null) {
      visitedNodes += firstNode.objectId
    }
    return generateSequence(firstNode) { node ->
      val nextNode = node[nodeClassName, nodeNextFieldName]!!.valueAsInstance
      if (nextNode != null && visitedNodes.add(nextNode.objectId)) {
        nextNode
      } else {
        null
      }
    }
      .withIndex()
      .mapNotNull { (index, node) ->
        val item = node[nodeClassName, nodeElementFieldName]!!.value
        // A list element can be null, and readers only surface non null references.
        if (!item.isNonNullReference) {
          return@mapNotNull null
        }
        Reference(
          valueObjectId = item.asObjectId!!,
          isLowPriority = false,
          lazyDetailsResolver = {
            LazyDetails(
              // All entries are represented by the same key name, e.g. "key()"
              name = "$index",
              locationClassObjectId = instanceClassId,
              locationType = ARRAY_ENTRY,
              isVirtual = true,
              matchedLibraryLeak = null
            )
          }
        )
      }
  }
}
