package shark.internal

import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader
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

  override fun read(source: HeapInstance): Sequence<Reference> {
    val table = source[className, tableFieldName]!!.valueAsObjectArray
    return if (table != null) {
      val entries = table.readElements().mapNotNull { entryRef ->
        if (entryRef.isNonNullReference) {
          val entry = entryRef.asObject!!.asInstance!!
          generateSequence(entry) { node ->
            node[nodeClassName, nodeNextFieldName]!!.valueAsInstance
          }
        } else {
          null
        }
      }.flatten()

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

      if (keysOnly) {
        entries.mapNotNull { entry ->
          val key = entry[nodeClassName, nodeKeyFieldName]!!.value
          createKeyRef(key)
        }
      } else {
        entries.flatMap { entry ->
          val key = entry[nodeClassName, nodeKeyFieldName]!!.value
          val keyRef = createKeyRef(key)
          val value = entry[nodeClassName, nodeValueFieldName]!!.value
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
    } else {
      emptySequence()
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
        val itemObjectId = node[nodeClassName, nodeElementFieldName]!!.value.asObjectId
        itemObjectId?.run {
          Reference(
            valueObjectId = this,
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
}
