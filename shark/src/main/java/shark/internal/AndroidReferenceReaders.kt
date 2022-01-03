package shark.internal

import shark.HeapGraph
import shark.HeapObject.HeapInstance
import shark.ReferenceLocationType.ARRAY_ENTRY
import shark.internal.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader
import shark.internal.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader.OptionalFactory
import shark.internal.Reference.LazyDetails

internal enum class AndroidReferenceReaders : OptionalFactory {

  MESSAGE_QUEUE {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val messageQueueClass =
        graph.findClassByName("android.os.MessageQueue") ?: return null

      val messageQueueClassId = messageQueueClass.objectId

      return object : VirtualInstanceReferenceReader {
        override fun matches(instance: HeapInstance) =
          instance.instanceClassId == messageQueueClassId

        override fun read(source: HeapInstance): Sequence<Reference> {
          val firstMessage = source["android.os.MessageQueue", "mMessages"]!!.valueAsInstance
          return generateSequence(firstMessage) { node ->
            node["android.os.Message", "next"]!!.valueAsInstance
          }
            .withIndex()
            .mapNotNull { (index, node) ->
              Reference(
                valueObjectId = node.objectId,
                isLowPriority = false,
                lazyDetailsResolver = {
                  LazyDetails(
                    // All entries are represented by the same key name, e.g. "key()"
                    name = "$index",
                    locationClassObjectId = messageQueueClassId,
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
  }
}
