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
  var leakInfo = "┬\n"
  val lastElement = elements.last()
  val lastReachability = lastElement.leakStatusAndReason
  elements.dropLast(1)
      .forEachIndexed { index, leakTraceElement ->
        val currentReachability = elements[index].leakStatusAndReason
        leakInfo += """
        #├─ ${leakTraceElement.className}
        #│    Leaking: ${currentReachability.renderToString()}${if (leakTraceElement.labels.isNotEmpty()) leakTraceElement.labels.joinToString(
            "\n│    ", prefix = "\n│    "
        ) else ""}
        #│    ↓ ${getNextElementString(this, leakTraceElement, index)}
        #""".trimMargin("#")
      }
  leakInfo += """╰→ ${lastElement.className}
      #$ZERO_WIDTH_SPACE     Leaking: ${lastReachability.renderToString()}
    """.trimMargin("#")

  return leakInfo
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
  val simpleClassName = element.simpleClassName
  val referenceName = if (element.reference != null) ".${element.reference.displayName}" else ""
  val exclusionString =
    if (element.exclusion != null) ", matching exclusion ${element.exclusion.matching}" else ""
  val requiredSpaces =
    staticString.length + holderString.length + simpleClassName.length + "├─".length
  val leakString = if (maybeLeakCause) {
    "\n│$ELEMENT_DEFAULT_NEW_LINE_SPACE" + " ".repeat(
        requiredSpaces
    ) + "~".repeat(referenceName.length - 1)
  } else {
    ""
  }

  return staticString + holderString + simpleClassName + referenceName + exclusionString + leakString
}

private const val ZERO_WIDTH_SPACE = '\u200b'
private const val ELEMENT_DEFAULT_NEW_LINE_SPACE = "     "