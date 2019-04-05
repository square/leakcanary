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
package com.squareup.leakcanary.internal

import android.content.res.Resources
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.annotation.ColorRes
import com.squareup.leakcanary.LeakTrace
import com.squareup.leakcanary.LeakTraceElement
import com.squareup.leakcanary.LeakTraceElement.Type.STATIC_FIELD
import com.squareup.leakcanary.R
import com.squareup.leakcanary.Reachability

internal class DisplayLeakAdapter(resources: Resources) : BaseAdapter() {

  private var opened = BooleanArray(0)

  private var leakTrace: LeakTrace? = null
  private var referenceKey: String? = null
  private var referenceName = ""

  private val classNameColorHexString: String
  private val leakColorHexString: String
  private val referenceColorHexString: String
  private val extraColorHexString: String
  private val helpColorHexString: String

  init {
    classNameColorHexString = hexStringColor(resources, R.color.leak_canary_class_name)
    leakColorHexString = hexStringColor(resources, R.color.leak_canary_leak)
    referenceColorHexString = hexStringColor(resources, R.color.leak_canary_reference)
    extraColorHexString = hexStringColor(resources, R.color.leak_canary_extra)
    helpColorHexString = hexStringColor(resources, R.color.leak_canary_help)
  }

  override fun getView(
    position: Int,
    convertView: View?,
    parent: ViewGroup
  ): View {
    var convertView = convertView
    val context = parent.context
    if (getItemViewType(position) == TOP_ROW) {
      if (convertView == null) {
        convertView = LayoutInflater.from(context)
            .inflate(R.layout.leak_canary_ref_top_row, parent, false)
      }
      val textView = findById<TextView>(convertView!!, R.id.leak_canary_row_text)
      textView.text = context.packageName
    } else {
      if (convertView == null) {
        convertView = LayoutInflater.from(context)
            .inflate(R.layout.leak_canary_ref_row, parent, false)
      }

      val titleView = findById<TextView>(convertView!!, R.id.leak_canary_row_title)
      val detailView = findById<TextView>(convertView, R.id.leak_canary_row_details)
      val connector =
        findById<DisplayLeakConnectorView>(convertView, R.id.leak_canary_row_connector)
      val moreDetailsView = findById<MoreDetailsView>(convertView, R.id.leak_canary_row_more)

      connector.setType(getConnectorType(position))
      moreDetailsView.setOpened(opened[position])

      if (opened[position]) {
        detailView.visibility = View.VISIBLE
      } else {
        detailView.visibility = View.GONE
      }

      val resources = convertView.resources
      if (position == 1) {
        titleView.text = Html.fromHtml(
            "<font color='"
                + helpColorHexString
                + "'>"
                + "<b>" + resources.getString(R.string.leak_canary_help_title) + "</b>"
                + "</font>"
        )
        val detailText = Html.fromHtml(
            resources.getString(R.string.leak_canary_help_detail)
        ) as SpannableStringBuilder
        SquigglySpan.replaceUnderlineSpans(detailText, resources)
        detailView.text = detailText
      } else {
        val isLeakingInstance = position == count - 1
        val element = getItem(position)

        val reachability = leakTrace!!.expectedReachability[elementIndex(position)]
        val maybeLeakCause: Boolean
        if (isLeakingInstance || reachability.status == Reachability.Status.UNREACHABLE) {
          maybeLeakCause = false
        } else {
          val nextReachability = leakTrace!!.expectedReachability[elementIndex(position + 1)]
          maybeLeakCause = nextReachability.status != Reachability.Status.REACHABLE
        }

        val htmlTitle = htmlTitle(element!!, maybeLeakCause, resources)

        titleView.text = htmlTitle

        if (opened[position]) {
          val htmlDetail = htmlDetails(isLeakingInstance, element)
          detailView.text = htmlDetail
        }
      }
    }

    return convertView
  }

  private fun htmlTitle(
    element: LeakTraceElement,
    maybeLeakCause: Boolean,
    resources: Resources
  ): Spanned {
    var htmlString = ""

    var simpleName = element.getSimpleClassName()
    simpleName = simpleName.replace("[]", "[ ]")

    val styledClassName = "<font color='$classNameColorHexString'>$simpleName</font>"

    val reference = element.reference
    if (reference != null) {
      var referenceName = reference.displayName.replace("<".toRegex(), "&lt;")
          .replace(">".toRegex(), "&gt;")

      if (maybeLeakCause) {
        referenceName = "<u><font color='$leakColorHexString'>$referenceName</font></u>"
      } else {
        referenceName = "<font color='$referenceColorHexString'>$referenceName</font>"
      }

      if (reference.type == STATIC_FIELD) {
        referenceName = "<i>$referenceName</i>"
      }

      var classAndReference = "$styledClassName.$referenceName"

      if (maybeLeakCause) {
        classAndReference = "<b>$classAndReference</b>"
      }

      htmlString += classAndReference
    } else {
      htmlString += styledClassName
    }

    val exclusion = element.exclusion
    if (exclusion != null) {
      htmlString += " (excluded)"
    }
    val builder = Html.fromHtml(htmlString) as SpannableStringBuilder
    if (maybeLeakCause) {
      SquigglySpan.replaceUnderlineSpans(builder, resources)
    }

    return builder
  }

  private fun htmlDetails(
    isLeakingInstance: Boolean,
    element: LeakTraceElement
  ): Spanned {
    var htmlString = ""
    if (element.extra != null) {
      htmlString += " <font color='" + extraColorHexString + "'>" + element.extra + "</font>"
    }

    val exclusion = element.exclusion
    if (exclusion != null) {
      htmlString += "<br/><br/>Excluded by rule"
      if (exclusion.name != null) {
        htmlString += " <font color='#ffffff'>" + exclusion.name + "</font>"
      }
      htmlString += " matching <font color='#f3cf83'>" + exclusion.matching + "</font>"
      if (exclusion.reason != null) {
        htmlString += " because <font color='#f3cf83'>" + exclusion.reason + "</font>"
      }
    }
    htmlString += ("<br>"
        + "<font color='" + extraColorHexString + "'>"
        + element.toDetailedString().replace("\n", "<br>")
        + "</font>")

    if (isLeakingInstance && referenceName != "") {
      htmlString += " <font color='$extraColorHexString'>$referenceName</font>"
    }

    return Html.fromHtml(htmlString)
  }

  private fun getConnectorType(position: Int): DisplayLeakConnectorView.Type {
    if (position == 1) {
      return DisplayLeakConnectorView.Type.HELP
    } else if (position == 2) {
      if (leakTrace!!.expectedReachability.size == 1) {
        return DisplayLeakConnectorView.Type.START_LAST_REACHABLE
      }
      val nextReachability = leakTrace!!.expectedReachability[elementIndex(position + 1)]
      return if (nextReachability.status != Reachability.Status.REACHABLE) {
        DisplayLeakConnectorView.Type.START_LAST_REACHABLE
      } else DisplayLeakConnectorView.Type.START
    } else {
      val isLeakingInstance = position == count - 1
      if (isLeakingInstance) {
        val previousReachability = leakTrace!!.expectedReachability[elementIndex(position - 1)]
        return if (previousReachability.status != Reachability.Status.UNREACHABLE) {
          DisplayLeakConnectorView.Type.END_FIRST_UNREACHABLE
        } else DisplayLeakConnectorView.Type.END
      } else {
        val reachability = leakTrace!!.expectedReachability[elementIndex(position)]
        when (reachability.status) {
          Reachability.Status.UNKNOWN -> return DisplayLeakConnectorView.Type.NODE_UNKNOWN
          Reachability.Status.REACHABLE -> {
            val nextReachability = leakTrace!!.expectedReachability[elementIndex(position + 1)]
            return if (nextReachability.status != Reachability.Status.REACHABLE) {
              DisplayLeakConnectorView.Type.NODE_LAST_REACHABLE
            } else {
              DisplayLeakConnectorView.Type.NODE_REACHABLE
            }
          }
          Reachability.Status.UNREACHABLE -> {
            val previousReachability = leakTrace!!.expectedReachability[elementIndex(position - 1)]
            return if (previousReachability.status != Reachability.Status.UNREACHABLE) {
              DisplayLeakConnectorView.Type.NODE_FIRST_UNREACHABLE
            } else {
              DisplayLeakConnectorView.Type.NODE_UNREACHABLE
            }
          }
          else -> throw IllegalStateException("Unknown value: " + reachability.status)
        }
      }
    }
  }

  fun update(
    leakTrace: LeakTrace,
    referenceKey: String,
    referenceName: String
  ) {
    if (referenceKey == this.referenceKey) {
      // Same data, nothing to change.
      return
    }
    this.referenceKey = referenceKey
    this.referenceName = referenceName
    this.leakTrace = leakTrace
    opened = BooleanArray(2 + leakTrace.elements.size)
    notifyDataSetChanged()
  }

  fun toggleRow(position: Int) {
    opened[position] = !opened[position]
    notifyDataSetChanged()
  }

  override fun getCount(): Int {
    return if (leakTrace == null) {
      2
    } else 2 + leakTrace!!.elements.size
  }

  override fun getItem(position: Int): LeakTraceElement? {
    if (getItemViewType(position) == TOP_ROW) {
      return null
    }
    return if (position == 1) {
      null
    } else leakTrace!!.elements[elementIndex(position)]
  }

  private fun elementIndex(position: Int): Int {
    return position - 2
  }

  override fun getViewTypeCount(): Int {
    return 2
  }

  override fun getItemViewType(position: Int): Int {
    return if (position == 0) {
      TOP_ROW
    } else NORMAL_ROW
  }

  override fun getItemId(position: Int): Long {
    return position.toLong()
  }

  companion object {

    private const val TOP_ROW = 0
    private const val NORMAL_ROW = 1

    // https://stackoverflow.com/a/6540378/703646
    private fun hexStringColor(resources: Resources, @ColorRes colorResId: Int): String {
      return String.format("#%06X", 0xFFFFFF and resources.getColor(colorResId))
    }

    private fun <T : View> findById(
      view: View,
      id: Int
    ): T {
      @Suppress("UNCHECKED_CAST")
      return view.findViewById<View>(id) as T
    }
  }
}
