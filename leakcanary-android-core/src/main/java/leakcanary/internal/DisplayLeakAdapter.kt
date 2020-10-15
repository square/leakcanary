/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package leakcanary.internal

import android.content.Context
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.squareup.leakcanary.core.R
import leakcanary.internal.DisplayLeakConnectorView.Type
import leakcanary.internal.DisplayLeakConnectorView.Type.END
import leakcanary.internal.DisplayLeakConnectorView.Type.END_FIRST_UNREACHABLE
import leakcanary.internal.DisplayLeakConnectorView.Type.GC_ROOT
import leakcanary.internal.DisplayLeakConnectorView.Type.NODE_FIRST_UNREACHABLE
import leakcanary.internal.DisplayLeakConnectorView.Type.NODE_LAST_REACHABLE
import leakcanary.internal.DisplayLeakConnectorView.Type.NODE_REACHABLE
import leakcanary.internal.DisplayLeakConnectorView.Type.NODE_UNKNOWN
import leakcanary.internal.DisplayLeakConnectorView.Type.NODE_UNREACHABLE
import leakcanary.internal.DisplayLeakConnectorView.Type.START
import leakcanary.internal.DisplayLeakConnectorView.Type.START_LAST_REACHABLE
import leakcanary.internal.navigation.getColorCompat
import leakcanary.internal.navigation.inflate
import leakcanary.internal.utils.humanReadableByteCount
import shark.LeakTrace
import shark.LeakTrace.GcRootType.JAVA_FRAME
import shark.LeakTraceObject
import shark.LeakTraceObject.LeakingStatus.LEAKING
import shark.LeakTraceObject.LeakingStatus.NOT_LEAKING
import shark.LeakTraceObject.LeakingStatus.UNKNOWN
import shark.LeakTraceReference
import shark.LeakTraceReference.ReferenceType.STATIC_FIELD

@Suppress("DEPRECATION")
internal class DisplayLeakAdapter constructor(
  context: Context,
  private val leakTrace: LeakTrace,
  private val header: CharSequence
) : BaseAdapter() {

  private val highlightColorHexString: String
  private val leakColorHexString: String
  private val referenceColorHexString: String
  private val extraColorHexString: String
  private val helpColorHexString: String

  init {
    highlightColorHexString = hexStringColor(context, R.color.leak_canary_class_name)
    leakColorHexString = hexStringColor(context, R.color.leak_canary_leak)
    referenceColorHexString = hexStringColor(context, R.color.leak_canary_reference)
    extraColorHexString = hexStringColor(context, R.color.leak_canary_extra)
    helpColorHexString = hexStringColor(context, R.color.leak_canary_help)
  }

  override fun getView(
    position: Int,
    convertView: View?,
    parent: ViewGroup
  ): View {
    return when (getItemViewType(position)) {
      HEADER_ROW -> {
        val view = convertView ?: parent.inflate(R.layout.leak_canary_leak_header)
        bindHeaderRow(view)
        view
      }
      CONNECTOR_ROW -> {
        val view = convertView ?: parent.inflate(R.layout.leak_canary_ref_row)
        bindConnectorRow(view, position)
        view
      }
      else -> {
        throw IllegalStateException("Unexpected type ${getItemViewType(position)}")
      }
    }
  }

  private fun bindHeaderRow(
    view: View
  ) {
    view.findViewById<TextView>(R.id.leak_canary_header_text)
        .apply {
          movementMethod = LinkMovementMethod.getInstance()
          text = header
        }
  }

  private fun bindConnectorRow(
    view: View,
    position: Int
  ) {
    val titleView = view.findViewById<TextView>(R.id.leak_canary_row_title)
    val connector = view.findViewById<DisplayLeakConnectorView>(R.id.leak_canary_row_connector)

    connector.setType(getConnectorType(position))

    titleView.text = when {
      position == 1 -> {
        "GC Root: ${leakTrace.gcRootType.description}"
      }
      position < count - 1 -> {
        val referencePathIndex = elementIndex(position)
        val referencePath = leakTrace.referencePath[referencePathIndex]
        val isSuspect = leakTrace.referencePathElementIsSuspect(referencePathIndex)

        val leakTraceObject = referencePath.originObject

        val typeName =
          if (position == 2 && leakTrace.gcRootType == JAVA_FRAME) "thread" else leakTraceObject.typeName

        var referenceName = referencePath.referenceDisplayName

        referenceName = referenceName.replace("<".toRegex(), "&lt;")
            .replace(">".toRegex(), "&gt;")

        referenceName = if (isSuspect) {
          "<u><font color='$leakColorHexString'>$referenceName</font></u>"
        } else {
          "<font color='$referenceColorHexString'>$referenceName</font>"
        }

        if (referencePath.referenceType == STATIC_FIELD) {
          referenceName = "<i>$referenceName</i>"
        }

        if (isSuspect) {
          referenceName = "<b>$referenceName</b>"
        }

        val staticPrefix = if (referencePath.referenceType == STATIC_FIELD) "static " else ""

        val htmlString = leakTraceObject.asHtmlString(typeName) +
            "$INDENTATION$staticPrefix${referencePath.styledOwningClassSimpleName()}.$referenceName"

        val builder = Html.fromHtml(htmlString) as SpannableStringBuilder
        if (isSuspect) {
          SquigglySpan.replaceUnderlineSpans(builder, view.context)
        }
        builder
      }
      else -> {
        Html.fromHtml(leakTrace.leakingObject.asHtmlString(leakTrace.leakingObject.typeName))
      }
    }
  }

  private fun LeakTraceObject.asHtmlString(typeName: String): String {
    val packageEnd = className.lastIndexOf('.')

    val extra: (String) -> String = { "<font color='$extraColorHexString'>$it</font>" }

    val styledClassName = styledClassSimpleName()
    var htmlString =
      if (packageEnd != -1) "${extra(className.substring(
          0, packageEnd
      ))}.$styledClassName" else styledClassName
    htmlString += " ${extra(typeName)}<br>"

    val reachabilityString = when (leakingStatus) {
      UNKNOWN -> extra("UNKNOWN")
      NOT_LEAKING -> extra("NO (${leakingStatusReason})")
      LEAKING -> "YES" + extra(" (${leakingStatusReason})")
    }

    htmlString += "$INDENTATION${extra("Leaking: ")}$reachabilityString<br>"

    retainedHeapByteSize?.let {
      val humanReadableRetainedHeapSize = humanReadableByteCount(it.toLong(), si = true)
      htmlString += "${INDENTATION}${extra("Retaining ")}$humanReadableRetainedHeapSize${extra(
          " in "
      )}$retainedObjectCount${extra(" objects")}<br>"
    }

    labels.forEach { label ->
      htmlString += "$INDENTATION${extra(label)}<br>"
    }
    return htmlString
  }

  private fun LeakTraceObject.styledClassSimpleName(): String {
    val simpleName = classSimpleName.replace("[]", "[ ]")
    return "<font color='$highlightColorHexString'>$simpleName</font>"
  }

  private fun LeakTraceReference.styledOwningClassSimpleName(): String {
    val simpleName = owningClassSimpleName.replace("[]", "[ ]")
    return "<font color='$highlightColorHexString'>$simpleName</font>"
  }

  @Suppress("ReturnCount")
  private fun getConnectorType(position: Int): Type {
    if (position == 1) {
      return GC_ROOT
    } else if (position == 2) {
      return when (leakTrace.referencePath.size) {
        0 -> END_FIRST_UNREACHABLE
        1 -> START_LAST_REACHABLE
        else -> {
          val nextReachability = leakTrace.referencePath[1].originObject
          if (nextReachability.leakingStatus != NOT_LEAKING) {
            START_LAST_REACHABLE
          } else START
        }
      }
    } else {
      val isLeakingInstance = position == count - 1
      if (isLeakingInstance) {
        val previousReachability = leakTrace.referencePath.last()
            .originObject
        return if (previousReachability.leakingStatus != LEAKING) {
          END_FIRST_UNREACHABLE
        } else END
      } else {
        val reachability = leakTrace.referencePath[elementIndex(position)].originObject
        when (reachability.leakingStatus) {
          UNKNOWN -> return NODE_UNKNOWN
          NOT_LEAKING -> {
            val nextReachability =
              if (position + 1 == count - 1) leakTrace.leakingObject else leakTrace.referencePath[elementIndex(
                  position + 1
              )].originObject
            return if (nextReachability.leakingStatus != NOT_LEAKING) {
              NODE_LAST_REACHABLE
            } else {
              NODE_REACHABLE
            }
          }
          LEAKING -> {
            val previousReachability =
              leakTrace.referencePath[elementIndex(position - 1)].originObject
            return if (previousReachability.leakingStatus != LEAKING) {
              NODE_FIRST_UNREACHABLE
            } else {
              NODE_UNREACHABLE
            }
          }
          else -> throw IllegalStateException(
              "Unknown value: " + reachability.leakingStatus
          )
        }
      }
    }
  }

  override fun isEnabled(position: Int) = false

  override fun getCount() = leakTrace.referencePath.size + 3

  override fun getItem(position: Int) = when {
    position == 0 || position == 1 -> null
    position == count - 1 -> leakTrace.leakingObject
    else -> leakTrace.referencePath[elementIndex(position)]
  }

  private fun elementIndex(position: Int) = position - 2

  override fun getViewTypeCount() = 2

  override fun getItemViewType(position: Int) = if (position == 0) HEADER_ROW else CONNECTOR_ROW

  override fun getItemId(position: Int) = position.toLong()

  companion object {

    const val HEADER_ROW = 0
    const val CONNECTOR_ROW = 1
    val INDENTATION = "&nbsp;".repeat(4)

    // https://stackoverflow.com/a/6540378/703646
    private fun hexStringColor(
      context: Context,
      colorResId: Int
    ): String {
      return String.format("#%06X", 0xFFFFFF and context.getColorCompat(colorResId))
    }
  }
}
