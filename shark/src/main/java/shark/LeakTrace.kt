package shark

import shark.LeakNodeStatus.LEAKING
import shark.LeakNodeStatus.NOT_LEAKING
import shark.LeakNodeStatus.UNKNOWN
import shark.LeakTraceElement.Holder.ARRAY
import shark.LeakTraceElement.Holder.THREAD
import shark.LeakTraceElement.Type.STATIC_FIELD
import java.io.Serializable
import java.util.Locale

/**
 * A chain of references that constitute the shortest strong reference path from a GC root to the
 * leaking object. Fixing the leak usually means breaking one of the references in that chain.
 */
data class LeakTrace(
  val elements: List<LeakTraceElement>
) : Serializable {
  val leakCauses = elements.filterIndexed { index, _ ->
    elementMayBeLeakCause(index)
  }

  fun elementMayBeLeakCause(index: Int): Boolean {
    return when (elements[index].leakStatus) {
      UNKNOWN -> true
      NOT_LEAKING -> if (index < elements.lastIndex) {
        elements[index + 1].leakStatus != NOT_LEAKING
      } else {
        false
      }
      else -> false
    }
  }

  override fun toString(): String {
    var result = "┬"

    elements.forEachIndexed { index, element ->
      val isLast = index == elements.lastIndex
      val nodePrefix = if (!isLast) {
        "├─ "
      } else {
        "╰→ "
      }
      result += "\n" + nodePrefix + element.className

      val contentPrefix = if (!isLast) {
        "│    "
      } else {
        "$ZERO_WIDTH_SPACE     "
      }

      result += "\n" + contentPrefix + "Leaking: " + when (elements[index].leakStatus) {
        UNKNOWN -> "UNKNOWN"
        NOT_LEAKING -> "NO (${elements[index].leakStatusReason})"
        LEAKING -> "YES (${elements[index].leakStatusReason})"
      }

      for (label in element.labels) {
        result += "\n" + contentPrefix + label
      }

      if (!isLast) {
        result += "\n$contentPrefix↓ " + getNextElementString(this, element, index)
      }

    }
    return result
  }

  companion object {
    private fun getNextElementString(
      leakTrace: LeakTrace,
      element: LeakTraceElement,
      index: Int
    ): String {
      val maybeLeakCause = leakTrace.elementMayBeLeakCause(index)

      val staticString =
        if (element.reference != null && element.reference.type == STATIC_FIELD) "static " else ""
      val holderString =
        if (element.holder == ARRAY || element.holder == THREAD) {
          "${element.holder.name.toLowerCase(Locale.US)} "
        } else ""
      val simpleClassName = element.classSimpleName
      val referenceName = if (element.reference != null) ".${element.reference.displayName}" else ""
      val requiredSpaces =
        staticString.length + holderString.length + simpleClassName.length + "├─".length
      val leakString = if (maybeLeakCause) {
        "\n│$ELEMENT_DEFAULT_NEW_LINE_SPACE" + " ".repeat(
            requiredSpaces
        ) + "~".repeat(referenceName.length - 1)
      } else {
        ""
      }

      return staticString + holderString + simpleClassName + referenceName + leakString
    }

    private const val ZERO_WIDTH_SPACE = '\u200b'
    private const val ELEMENT_DEFAULT_NEW_LINE_SPACE = "     "
  }
}