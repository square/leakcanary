package shark

import shark.LeakTrace.GcRootType.JAVA_FRAME
import shark.LeakTraceObject.LeakingStatus.LEAKING
import shark.LeakTraceObject.LeakingStatus.NOT_LEAKING
import shark.LeakTraceObject.LeakingStatus.UNKNOWN
import shark.LeakTraceReference.ReferenceType.STATIC_FIELD
import shark.internal.createSHA1Hash
import java.io.Serializable
import kotlin.math.ln
import kotlin.math.pow

/**
 * The best strong reference path from a GC root to the leaking object. "Best" here means the
 * shortest prioritized path. A large number of distinct paths can generally be found leading
 * to a leaking object. Shark prioritizes paths that don't go through known
 * [LibraryLeakReferenceMatcher] (because those are known to create leaks so it's more interesting
 * to find other paths causing leaks), then it prioritize paths that don't go through java local
 * gc roots (because those are harder to reason about). Taking those priorities into account,
 * finding the shortest path means there are less [LeakTraceReference] that can be suspected to
 * cause the leak.
 */
data class LeakTrace(
  /**
   * The Garbage Collection root that references the [LeakTraceReference.originObject] in
   * the first [LeakTraceReference] of [referencePath].
   */
  val gcRootType: GcRootType,
  val referencePath: List<LeakTraceReference>,
  val leakingObject: LeakTraceObject
) : Serializable {

  /**
   * The minimum number of bytes which would be freed if the leak was fixed.
   * Null if the retained heap size was not computed.
   */
  val retainedHeapByteSize: Int?
    get() {
      val allObjects = listOf(leakingObject) + referencePath.map { it.originObject }
      return allObjects.filter { it.leakingStatus == LEAKING }
          .mapNotNull { it.retainedHeapByteSize }
          // The minimum released is the max held by a leaking object.
          .max()
    }

  /**
   * The minimum number of objects which would be unreachable if the leak was fixed. Null if the
   * retained heap size was not computed.
   */
  val retainedObjectCount: Int?
    get() {
      val allObjects = listOf(leakingObject) + referencePath.map { it.originObject }
      return allObjects.filter { it.leakingStatus == LEAKING }
          .mapNotNull { it.retainedObjectCount }
          // The minimum released is the max held by a leaking object.
          .max()
    }

  /**
   * A part of [referencePath] that contains the references suspected to cause the leak.
   * Starts at the last non leaking object and ends before the first leaking object.
   */
  val suspectReferenceSubpath
    get() = referencePath.asSequence()
        .filterIndexed { index, _ ->
          referencePathElementIsSuspect(index)
        }

  /**
   * A SHA1 hash that represents this leak trace. This can be useful to group together similar
   * leak traces.
   *
   * The signature is a hash of [suspectReferenceSubpath].
   */
  val signature: String
    get() = suspectReferenceSubpath
        .joinToString(separator = "") { element ->
          element.originObject.className + element.referenceGenericName
        }
        .createSHA1Hash()

  /**
   * Returns true if the [referencePath] element at the provided [index] contains a reference
   * that is suspected to cause the leak, ie if [index] is greater than or equal to the index
   * of the [LeakTraceReference] of the last non leaking object and strictly lower than the index
   * of the [LeakTraceReference] of the first leaking object.
   */
  fun referencePathElementIsSuspect(index: Int): Boolean {
    return when (referencePath[index].originObject.leakingStatus) {
      UNKNOWN -> true
      NOT_LEAKING -> index == referencePath.lastIndex ||
          referencePath[index + 1].originObject.leakingStatus != NOT_LEAKING
      else -> false
    }
  }

  override fun toString(): String = leakTraceAsString(showLeakingStatus = true)

  fun toSimplePathString(): String = leakTraceAsString(showLeakingStatus = false)

  // https://stackoverflow.com/a/3758880
  private fun humanReadableByteCount(
          bytes: Long,
          si: Boolean
  ): String {
    val unit = if (si) 1000 else 1024
    if (bytes < unit) return "$bytes B"
    val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
    val pre = (if (si) "kMGTPE" else "KMGTPE")[exp - 1] + if (si) "" else "i"
    return String.format("%.1f %sB", bytes / unit.toDouble().pow(exp.toDouble()), pre)
  }

  private fun leakTraceAsString(showLeakingStatus: Boolean): String {
    var result = """
        ┬───
        │ GC Root: ${gcRootType.description}
        │
      """.trimIndent()

    referencePath.forEachIndexed { index, element ->
      val originObject = element.originObject
      val leakStatus = when (originObject.leakingStatus) {
        UNKNOWN -> "UNKNOWN"
        NOT_LEAKING -> "NO (${originObject.leakingStatusReason})"
        LEAKING -> "YES (${originObject.leakingStatusReason})"
      }

      /**
       * When the GC Root is a Java Frame, Shark inserts the corresponding thread as an extra
       * element in the leaktrace.
       */
      val typeName =
        if (index == 0 && gcRootType == JAVA_FRAME) "thread" else originObject.typeName

      result += "\n├─ ${originObject.className} $typeName"
      if (showLeakingStatus) {
        result += "\n│    Leaking: $leakStatus"
      }
      if (originObject.retainedHeapByteSize != null) {
        val humanReadableRetainedHeapSize = humanReadableByteCount(originObject.retainedHeapByteSize.toLong(), si = true)
        result += "\n│    Retaining $humanReadableRetainedHeapSize in ${originObject.retainedObjectCount} objects"
      }

      for (label in originObject.labels) {
        result += "\n│    $label"
      }
      result += getNextElementString(this, element, index, showLeakingStatus)
    }

    result += "\n"
    result += "╰→ ${leakingObject.className} ${leakingObject.typeName}"
    if (showLeakingStatus) {
      result += "\n$ZERO_WIDTH_SPACE"
      result += "     Leaking: YES (${leakingObject.leakingStatusReason})"
    }
    if (leakingObject.retainedHeapByteSize != null) {
      result += "\n$ZERO_WIDTH_SPACE"
      val humanReadableRetainedHeapSize = humanReadableByteCount(leakingObject.retainedHeapByteSize.toLong(), si = true)
      result += "     Retaining $humanReadableRetainedHeapSize in ${leakingObject.retainedObjectCount} objects"
    }
    for (label in leakingObject.labels) {
      result += "\n$ZERO_WIDTH_SPACE"
      result += "     $label"
    }

    return result
  }

  enum class GcRootType(val description: String) {
    JNI_GLOBAL("Global variable in native code"),
    JNI_LOCAL("Local variable in native code"),
    JAVA_FRAME("Java local variable"),
    NATIVE_STACK("Input or output parameters in native code"),
    STICKY_CLASS("System class"),
    THREAD_BLOCK("Thread block"),
    MONITOR_USED(
        "Monitor (anything that called the wait() or notify() methods, or that is synchronized.)"
    ),
    THREAD_OBJECT("Thread object"),
    JNI_MONITOR("Root JNI monitor"),
    ;

    companion object {
      fun fromGcRoot(gcRoot: GcRoot): GcRootType = when (gcRoot) {
        is GcRoot.JniGlobal -> JNI_GLOBAL
        is GcRoot.JniLocal -> JNI_LOCAL
        is GcRoot.JavaFrame -> JAVA_FRAME
        is GcRoot.NativeStack -> NATIVE_STACK
        is GcRoot.StickyClass -> STICKY_CLASS
        is GcRoot.ThreadBlock -> THREAD_BLOCK
        is GcRoot.MonitorUsed -> MONITOR_USED
        is GcRoot.ThreadObject -> THREAD_OBJECT
        is GcRoot.JniMonitor -> JNI_MONITOR
        else -> throw IllegalStateException("Unexpected gc root $gcRoot")
      }

    }
  }

  /** This field is kept to support backward compatible deserialization. */
  private val elements: List<LeakTraceElement>? = null

  internal fun fromV20(retainedHeapByteSize: Int?) = LeakTrace(
      gcRootType = elements!!.first().gcRootTypeFromV20(),
      referencePath = when {
        elements.isEmpty() -> emptyList()
        else -> elements
            .subList(0, elements.lastIndex - 1)
            .map { it.referencePathElementFromV20() }
      },
      leakingObject = elements.last().originObjectFromV20()
  )

  companion object {
    private fun getNextElementString(
      leakTrace: LeakTrace,
      reference: LeakTraceReference,
      index: Int,
      showLeakingStatus: Boolean
    ): String {
      val static = if (reference.referenceType == STATIC_FIELD) " static" else ""
      val referenceLine =
        "    ↓$static ${reference.owningClassSimpleName}.${reference.referenceDisplayName}"

      return if (showLeakingStatus && leakTrace.referencePathElementIsSuspect(index)) {
        val lengthBeforeReferenceName = referenceLine.lastIndexOf('.') + 1
        val referenceLength = referenceLine.length - lengthBeforeReferenceName

        val spaces = " ".repeat(lengthBeforeReferenceName)
        val underline = "~".repeat(referenceLength)
        "\n│$referenceLine\n│$spaces$underline"
      } else {
        "\n│$referenceLine"
      }
    }

    private const val ZERO_WIDTH_SPACE = '\u200b'
    private const val serialVersionUID = -6315725584154386429

  }
}