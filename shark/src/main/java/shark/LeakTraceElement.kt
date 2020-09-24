package shark

import shark.LeakNodeStatus.LEAKING
import shark.LeakNodeStatus.NOT_LEAKING
import shark.LeakNodeStatus.UNKNOWN
import shark.LeakTrace.GcRootType
import shark.LeakTraceElement.Holder.ARRAY
import shark.LeakTraceElement.Holder.CLASS
import shark.LeakTraceElement.Holder.OBJECT
import shark.LeakTraceElement.Holder.THREAD
import shark.LeakTraceObject.LeakingStatus
import shark.LeakTraceObject.ObjectType
import java.io.Serializable

/**
 * This class is kept to support backward compatible deserialization.
 */
internal class LeakTraceElement : Serializable {

  private val reference: LeakReference? = null
  private val holder: Holder? = null
  private val className: String? = null
  private val labels: Set<String>? = null
  private val leakStatus: LeakNodeStatus? = null
  private val leakStatusReason: String? = null

  enum class Type {
    INSTANCE_FIELD,
    STATIC_FIELD,
    LOCAL,
    ARRAY_ENTRY
  }

  enum class Holder {
    OBJECT,
    CLASS,
    THREAD,
    ARRAY
  }

  fun gcRootTypeFromV20() = when (val gcRootLabel = labels!!
      .first {
        it.startsWith("GC Root: ")
      }
      .substring("GC Root: ".length)) {
    "Thread object" -> GcRootType.THREAD_OBJECT
    "Global variable in native code" -> GcRootType.JNI_GLOBAL
    "Local variable in native code" -> GcRootType.JNI_LOCAL
    "Java local variable" -> GcRootType.JAVA_FRAME
    "Input or output parameters in native code" -> GcRootType.NATIVE_STACK
    "System class" -> GcRootType.STICKY_CLASS
    "Thread block" -> GcRootType.THREAD_BLOCK
    "Monitor (anything that called the wait() or notify() methods, or that is synchronized.)" -> GcRootType.MONITOR_USED
    "Root JNI monitor" -> GcRootType.JNI_MONITOR
    else -> throw IllegalStateException("Unexpected gc root label $gcRootLabel")
  }

  fun referencePathElementFromV20() = reference!!.fromV20(originObjectFromV20())

  fun originObjectFromV20() = LeakTraceObject(
      type = when (holder!!) {
        OBJECT -> ObjectType.INSTANCE
        CLASS -> ObjectType.CLASS
        THREAD -> ObjectType.INSTANCE
        ARRAY -> ObjectType.ARRAY
      },
      className = className!!,
      labels = labels!!.filter { !it.startsWith("GC Root: ") }.toSet(),
      leakingStatus = when (leakStatus!!) {
        NOT_LEAKING -> LeakingStatus.NOT_LEAKING
        LEAKING -> LeakingStatus.LEAKING
        UNKNOWN -> LeakingStatus.UNKNOWN
      },
      leakingStatusReason = leakStatusReason!!,
      retainedHeapByteSize = null,
      retainedObjectCount = null
  )

  companion object {
    private const val serialVersionUID: Long = -6795139831875582552
  }
}