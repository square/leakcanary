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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.squareup.leakcanary.core.R
import leakcanary.LeakNodeStatus.LEAKING
import leakcanary.LeakNodeStatus.NOT_LEAKING
import leakcanary.LeakNodeStatus.UNKNOWN
import leakcanary.LeakTrace
import leakcanary.LeakTraceElement
import leakcanary.LeakTraceElement.Type.STATIC_FIELD
import leakcanary.internal.DisplayLeakConnectorView.Type
import leakcanary.internal.DisplayLeakConnectorView.Type.END
import leakcanary.internal.DisplayLeakConnectorView.Type.END_FIRST_UNREACHABLE
import leakcanary.internal.DisplayLeakConnectorView.Type.HELP
import leakcanary.internal.DisplayLeakConnectorView.Type.HELP_LEAK_GROUP
import leakcanary.internal.DisplayLeakConnectorView.Type.NODE_FIRST_UNREACHABLE
import leakcanary.internal.DisplayLeakConnectorView.Type.NODE_LAST_REACHABLE
import leakcanary.internal.DisplayLeakConnectorView.Type.NODE_REACHABLE
import leakcanary.internal.DisplayLeakConnectorView.Type.NODE_UNKNOWN
import leakcanary.internal.DisplayLeakConnectorView.Type.NODE_UNREACHABLE
import leakcanary.internal.DisplayLeakConnectorView.Type.START
import leakcanary.internal.DisplayLeakConnectorView.Type.START_LAST_REACHABLE
import leakcanary.internal.MoreDetailsView.Details.CLOSED
import leakcanary.internal.MoreDetailsView.Details.NONE
import leakcanary.internal.MoreDetailsView.Details.OPENED
import leakcanary.internal.activity.db.LeakingInstanceTable.InstanceProjection
import leakcanary.internal.navigation.inflate

internal class DisplayLeakAdapter private constructor(
  context: Context,
  private val leakTrace: LeakTrace,
  private val referenceName: String,
  private val instanceProjections: List<InstanceProjection>
) : BaseAdapter() {

  private val isLeakGroup = instanceProjections.isNotEmpty()

  constructor(
    context: Context,
    leakTrace: LeakTrace,
    referenceName: String
  ) : this(context, leakTrace, referenceName, emptyList())

  constructor(
    context: Context,
    leakTrace: LeakTrace,
    instanceProjections: List<InstanceProjection>
  ) : this(context, leakTrace, "", instanceProjections)

  private val opened = BooleanArray(TOP_ROW_COUNT + leakTrace.elements.size)

  private val classNameColorHexString: String
  private val leakColorHexString: String
  private val referenceColorHexString: String
  private val extraColorHexString: String
  private val helpColorHexString: String

  init {
    classNameColorHexString = hexStringColor(context, R.color.leak_canary_class_name)
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
      TOP_ROW -> {
        val view = convertView ?: parent.inflate(R.layout.leak_canary_ref_top_row)
        bindTopRow(view)
        view
      }
      CONNECTOR_ROW -> {
        val view = convertView ?: parent.inflate(R.layout.leak_canary_ref_row)
        bindConnectorRow(view, position)
        view
      }
      else -> {
        val view = convertView ?: parent.inflate(R.layout.leak_canary_leak_row)
        bindLeakInstanceRow(view, position)
        view
      }
    }
  }

  private fun bindTopRow(view: View) {
    val textView = view.findViewById<TextView>(R.id.leak_canary_row_text)
    textView.text = view.context.packageName
  }

  private fun bindConnectorRow(
    view: View,
    position: Int
  ) {
    val titleView = view.findViewById<TextView>(R.id.leak_canary_row_title)
    val detailView = view.findViewById<TextView>(R.id.leak_canary_row_details)
    val connector = view.findViewById<DisplayLeakConnectorView>(R.id.leak_canary_row_connector)
    val moreDetailsView = view.findViewById<MoreDetailsView>(R.id.leak_canary_row_more)

    connector.setType(getConnectorType(position))

    moreDetailsView.setDetails(
        when {
          isLeakGroup -> NONE
          // Learn more row
          isFirstConnectorRow(position) -> NONE
          opened[position] -> OPENED
          else -> CLOSED
        }
    )

    if (opened[position]) {
      detailView.visibility = View.VISIBLE
    } else {
      detailView.visibility = View.GONE
    }

    val resources = view.resources
    if (isFirstConnectorRow(position)) {
      titleView.text = if (isLeakGroup) {
        HtmlCompat.fromHtml(
            """
              <font color='$helpColorHexString'>
                <b>Known likely causes of leak group</b>
              </font>
            """,
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
      } else {
        HtmlCompat.fromHtml(
            """
              <font color='$helpColorHexString'>
                <b>${resources.getString(R.string.leak_canary_help_title)}</b>
              </font>
            """,
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
      }
    } else {
      val isLast = position == (TOP_ROW_COUNT + leakTrace.elements.size) - 1

      val elementIndex = elementIndex(position)
      val element = leakTrace.elements[elementIndex]

      val maybeLeakCause = if (isLeakGroup) {
        true
      } else leakTrace.elementMayBeLeakCause(elementIndex)

      val htmlTitle = htmlTitle(element, maybeLeakCause, view.context)

      titleView.text = htmlTitle

      if (opened[position]) {
        val htmlDetail = htmlDetails(isLast, element)
        detailView.text = htmlDetail
      }
    }
  }

  private fun bindLeakInstanceRow(
    view: View,
    position: Int
  ) {
    val titleView = view.findViewById<TextView>(R.id.leak_canary_row_text)
    val timeView = view.findViewById<TextView>(R.id.leak_canary_row_time)

    val projection = instanceProjections[position - TOP_ROW_COUNT - leakTrace.elements.size]

    titleView.text =
      view.resources.getString(R.string.leak_canary_class_has_leaked, projection.classSimpleName)

    timeView.text = DateUtils.formatDateTime(
        view.context, projection.createdAtTimeMillis,
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
    )
  }

  private fun htmlTitle(
    element: LeakTraceElement,
    maybeLeakCause: Boolean,
    context: Context
  ): Spanned {

    val packageEnd = element.className.lastIndexOf('.')
    var simpleName = element.classSimpleName
    simpleName = simpleName.replace("[]", "[ ]")
    val styledClassName = "<font color='$classNameColorHexString'>$simpleName</font>"

    var htmlString =
      if (packageEnd != -1) "<font color='$extraColorHexString'>${element.className.substring(
          0, packageEnd
      )}</font>.$styledClassName" else styledClassName

    htmlString += "<br>"

    val reachabilityString = when (element.leakStatusAndReason.status) {
      UNKNOWN -> "UNKNOWN"
      NOT_LEAKING -> "NO (${element.leakStatusAndReason.reason})"
      LEAKING -> "YES (${element.leakStatusAndReason.reason})"
    }

    val indentation = "&nbsp;".repeat(4)
    htmlString += "$indentation<font color='$extraColorHexString'>Leaking: $reachabilityString</font><br>"

    element.labels.forEach { label ->
      htmlString += "$indentation<font color='$extraColorHexString'>$label</font><br>"
    }

    val reference = element.reference
    if (reference != null) {
      var referenceName = reference.displayName.replace("<".toRegex(), "&lt;")
          .replace(">".toRegex(), "&gt;")

      referenceName = if (maybeLeakCause) {
        "<u><font color='$leakColorHexString'>$referenceName</font></u>"
      } else {
        "<font color='$referenceColorHexString'>$referenceName</font>"
      }

      if (reference.type == STATIC_FIELD) {
        referenceName = "<i>$referenceName</i>"
      }

      htmlString += "$indentation$styledClassName.${if (maybeLeakCause) "<b>$referenceName</b>" else referenceName}"
    }

    val exclusion = element.exclusion
    if (exclusion != null) {
      htmlString += " (excluded)"
    }
    val builder =
      HtmlCompat.fromHtml(htmlString, HtmlCompat.FROM_HTML_MODE_LEGACY) as SpannableStringBuilder
    if (maybeLeakCause) {
      SquigglySpan.replaceUnderlineSpans(builder, context)
    }

    return builder
  }

  private fun htmlDetails(
    isLeakingInstance: Boolean,
    element: LeakTraceElement
  ): Spanned {
    var htmlString = ""
    val exclusion = element.exclusion
    if (exclusion != null) {
      htmlString += "<br/><br/>Excluded by rule"
      htmlString += " matching <font color='#f3cf83'>" + exclusion.matching + "</font>"
      if (exclusion.reason != null) {
        htmlString += " because <font color='#f3cf83'>" + exclusion.reason + "</font>"
      }
    }
    if (isLeakingInstance && referenceName != "") {
      htmlString += " <font color='$extraColorHexString'>$referenceName</font>"
    }

    return HtmlCompat.fromHtml(htmlString, HtmlCompat.FROM_HTML_MODE_LEGACY)
  }

  private fun getConnectorType(position: Int): Type {
    if (isFirstConnectorRow(position)) {
      return if (isLeakGroup) HELP_LEAK_GROUP else HELP
    } else if (position == TOP_ROW_COUNT) {
      if (leakTrace.elements.size == 1) {
        return START_LAST_REACHABLE
      }
      val nextReachability = leakTrace.elements[elementIndex(position + 1)]
      return if (nextReachability.leakStatusAndReason.status != NOT_LEAKING) {
        START_LAST_REACHABLE
      } else START
    } else {
      val isLeakingInstance = position == count - 1
      if (isLeakingInstance) {
        val previousReachability = leakTrace.elements[elementIndex(position - 1)]
        return if (previousReachability.leakStatusAndReason.status != LEAKING) {
          END_FIRST_UNREACHABLE
        } else END
      } else {
        val reachability = leakTrace.elements[elementIndex(position)]
        when (reachability.leakStatusAndReason.status) {
          UNKNOWN -> return NODE_UNKNOWN
          NOT_LEAKING -> {
            val nextReachability = leakTrace.elements[elementIndex(position + 1)]
            return if (nextReachability.leakStatusAndReason.status != NOT_LEAKING) {
              NODE_LAST_REACHABLE
            } else {
              NODE_REACHABLE
            }
          }
          LEAKING -> {
            val previousReachability = leakTrace.elements[elementIndex(position - 1)]
            return if (previousReachability.leakStatusAndReason.status != LEAKING) {
              NODE_FIRST_UNREACHABLE
            } else {
              NODE_UNREACHABLE
            }
          }
          else -> throw IllegalStateException("Unknown value: " + reachability.leakStatusAndReason.status)
        }
      }
    }
  }

  fun isLearnMoreRow(position: Int) = isFirstConnectorRow(position) && !isLeakGroup

  fun isFirstConnectorRow(position: Int) = position == TOP_ROW_COUNT - 1

  fun toggleRow(position: Int) {
    opened[position] = !opened[position]
    notifyDataSetChanged()
  }

  override fun getCount() = TOP_ROW_COUNT + leakTrace.elements.size + instanceProjections.size

  override fun getItem(position: Int) = null

  private fun elementIndex(position: Int): Int {
    return position - TOP_ROW_COUNT
  }

  override fun getViewTypeCount() = 3

  override fun getItemViewType(position: Int) = when {
    position == 0 -> TOP_ROW
    position < TOP_ROW_COUNT + leakTrace.elements.size -> CONNECTOR_ROW
    else -> LEAK_ROW
  }

  override fun getItemId(position: Int) = position.toLong()

  companion object {

    private const val TOP_ROW = 0
    private const val CONNECTOR_ROW = 1
    private const val LEAK_ROW = 2
    private const val TOP_ROW_COUNT = 2

    // https://stackoverflow.com/a/6540378/703646
    private fun hexStringColor(context: Context, @ColorRes colorResId: Int): String {
      return String.format("#%06X", 0xFFFFFF and ContextCompat.getColor(context, colorResId))
    }
  }
}
