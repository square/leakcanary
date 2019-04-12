package leakcanary.internal

import leakcanary.LeakTrace
import leakcanary.LeakTraceElement
import leakcanary.LeakTraceElement.Holder.ARRAY
import leakcanary.LeakTraceElement.Holder.THREAD
import leakcanary.LeakTraceElement.Type.STATIC_FIELD
import leakcanary.Reachability
import leakcanary.Reachability.Status.REACHABLE
import leakcanary.Reachability.Status.UNKNOWN
import leakcanary.Reachability.Status.UNREACHABLE
import java.util.Locale

fun LeakTrace.renderToString(): String {
  var leakInfo = "┬\n"
  val lastElement = elements.last()
  val lastReachability = expectedReachability.last()
  elements.dropLast(1)
      .forEachIndexed { index, leakTraceElement ->
        val currentReachability = expectedReachability[index]
        leakInfo += """
        #├─ ${leakTraceElement.className}
        #│    Leaking: ${currentReachability.renderToString()}
        #│    ↓ ${getNextElementString(this, leakTraceElement, index)}
        #""".trimMargin("#")
      }
  leakInfo += """╰→ ${lastElement.className}
      #$ZERO_WIDTH_SPACE     Leaking: ${lastReachability.renderToString()}
    """.trimMargin("#")

  return leakInfo
}

private fun Reachability.renderToString(): String {
  return when (status) {
    UNKNOWN -> "UNKNOWN"
    REACHABLE -> "NO ($reason)"
    UNREACHABLE -> "YES ($reason)"
  }
}

private fun getNextElementString(
  leakTrace: LeakTrace,
  element: LeakTraceElement,
  index: Int
): String {
  val maybeLeakCause = leakTrace.elementMayBeLeakCause(index)

  val staticString =
    if (element.reference != null && element.reference.type == STATIC_FIELD) "static" else ""
  val holderString =
    if (element.holder == ARRAY || element.holder == THREAD) {
      "${element.holder.name.toLowerCase(Locale.US)} "
    } else ""
  val simpleClassName = element.getSimpleClassName()
  val referenceName = if (element.reference != null) ".${element.reference.displayName}" else ""
  val extraString = if (element.extra != null) " ${element.extra}" else ""
  val exclusionString =
    if (element.exclusion != null) " , matching exclusion ${element.exclusion.matching}" else ""
  val requiredSpaces =
    staticString.length + holderString.length + simpleClassName.length + "├─".length
  val leakString = if (maybeLeakCause) {
    "\n│$ELEMENT_DEFAULT_NEW_LINE_SPACE" + " ".repeat(
        requiredSpaces
    ) + "~".repeat(referenceName.length - 1)
  } else {
    ""
  }

  return staticString + holderString + simpleClassName + referenceName + extraString + exclusionString + leakString
}

private const val ZERO_WIDTH_SPACE = '\u200b'
private const val ELEMENT_DEFAULT_NEW_LINE_SPACE = "     "