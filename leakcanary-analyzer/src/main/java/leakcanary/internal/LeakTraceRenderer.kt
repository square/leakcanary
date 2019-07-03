package leakcanary.internal

import leakcanary.LeakNodeStatus.LEAKING
import leakcanary.LeakNodeStatus.NOT_LEAKING
import leakcanary.LeakNodeStatus.UNKNOWN
import leakcanary.LeakNodeStatusAndReason
import leakcanary.LeakTrace
import leakcanary.LeakTraceElement
import leakcanary.LeakTraceElement.Holder.ARRAY
import leakcanary.LeakTraceElement.Holder.THREAD
import leakcanary.LeakTraceElement.Type.STATIC_FIELD
import java.util.Locale

fun LeakTrace.renderToString(): String {
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

    val currentReachability = elements[index].leakStatusAndReason
    result += "\n" + contentPrefix + "Leaking: " + currentReachability.renderToString()

    if (element.exclusion != null) {
      result += "\n" + contentPrefix + "Matches exclusion ${element.exclusion.matching}"
    }

    for (label in element.labels) {
      result += "\n" + contentPrefix + label
    }

    if (!isLast) {
      result += "\n" + contentPrefix + "↓ " + getNextElementString(this, element, index)
    }

  }
  return result
}

private fun LeakNodeStatusAndReason.renderToString(): String {
  return when (status) {
    UNKNOWN -> "UNKNOWN"
    NOT_LEAKING -> "NO ($reason)"
    LEAKING -> "YES ($reason)"
  }
}

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