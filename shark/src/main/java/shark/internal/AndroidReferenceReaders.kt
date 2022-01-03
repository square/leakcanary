package shark.internal

import shark.HeapGraph
import shark.HeapObject.HeapInstance
import shark.ValueHolder.ReferenceHolder
import shark.internal.ReferenceLocationType.ARRAY_ENTRY
import shark.internal.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader
import shark.internal.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader.OptionalFactory
import shark.internal.Reference.LazyDetails
import shark.internal.ReferenceLocationType.INSTANCE_FIELD

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
  },

  ANIMATOR_WEAK_REF_SUCKS {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val objectAnimatorClass =
        graph.findClassByName("android.animation.ObjectAnimator") ?: return null

      val weakRefClassId =
        graph.findClassByName("java.lang.ref.WeakReference")?.objectId ?: return null

      val objectAnimatorClassId = objectAnimatorClass.objectId

      return object : VirtualInstanceReferenceReader {
        override fun matches(instance: HeapInstance) =
          instance.instanceClassId == objectAnimatorClassId

        override fun read(source: HeapInstance): Sequence<Reference> {
          val mTarget = source["android.animation.ObjectAnimator", "mTarget"]?.valueAsInstance
            ?: return emptySequence()

          if (mTarget.instanceClassId != weakRefClassId) {
            return emptySequence()
          }

          val actualRef =
            mTarget["java.lang.ref.Reference", "referent"]!!.value.holder as ReferenceHolder

          return if (actualRef.isNull) {
            emptySequence()
          } else {
            sequenceOf(Reference(
              valueObjectId = actualRef.value,
              isLowPriority = true,
              lazyDetailsResolver = {
                LazyDetails(
                  name = "mTarget",
                  locationClassObjectId = objectAnimatorClassId,
                  locationType = INSTANCE_FIELD,
                  matchedLibraryLeak = null,
                  isVirtual = true
                )
              }
            ))
          }
        }
      }
    }
  }
}
