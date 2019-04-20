/*
 * Copyright (C) 2018 Square, Inc.
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
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.squareup.leakcanary.core.R

internal class RowElementLayout(
  context: Context,
  attrs: AttributeSet
) : ViewGroup(context, attrs) {

  private val connectorWidth: Int
  private val rowMargins: Int
  private val moreSize: Int
  private val minHeight: Int
  private val titleMarginTop: Int
  private val moreMarginTop: Int

  private var connector: View? = null
  private var moreButton: View? = null
  private var title: View? = null
  private var details: View? = null

  init {
    val resources = resources
    connectorWidth = resources.getDimensionPixelSize(R.dimen.leak_canary_connector_width)
    rowMargins = resources.getDimensionPixelSize(R.dimen.leak_canary_row_margins)
    moreSize = resources.getDimensionPixelSize(R.dimen.leak_canary_more_size)
    minHeight = resources.getDimensionPixelSize(R.dimen.leak_canary_row_min)
    titleMarginTop = resources.getDimensionPixelSize(R.dimen.leak_canary_row_title_margin_top)
    moreMarginTop = resources.getDimensionPixelSize(R.dimen.leak_canary_more_margin_top)
  }

  override fun onFinishInflate() {
    super.onFinishInflate()
    connector = findViewById(R.id.leak_canary_row_connector)
    moreButton = findViewById(R.id.leak_canary_row_more)
    title = findViewById(R.id.leak_canary_row_title)
    details = findViewById(R.id.leak_canary_row_details)
  }

  override fun onMeasure(
    widthMeasureSpec: Int,
    heightMeasureSpec: Int
  ) {
    val availableWidth = View.MeasureSpec.getSize(widthMeasureSpec)
    val titleWidth = availableWidth - connectorWidth - moreSize - 4 * rowMargins
    val titleWidthSpec = View.MeasureSpec.makeMeasureSpec(titleWidth, View.MeasureSpec.AT_MOST)
    val titleHeightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    title!!.measure(titleWidthSpec, titleHeightSpec)

    val moreSizeSpec = View.MeasureSpec.makeMeasureSpec(moreSize, View.MeasureSpec.EXACTLY)
    moreButton!!.measure(moreSizeSpec, moreSizeSpec)

    var totalHeight = titleMarginTop + title!!.measuredHeight

    val detailsWidth = availableWidth - connectorWidth - 3 * rowMargins
    val detailsWidthSpec = View.MeasureSpec.makeMeasureSpec(detailsWidth, View.MeasureSpec.AT_MOST)
    val detailsHeightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    details!!.measure(detailsWidthSpec, detailsHeightSpec)
    if (details!!.visibility != View.GONE) {
      totalHeight += details!!.measuredHeight
    }
    totalHeight = Math.max(totalHeight, minHeight)

    val connectorWidthSpec =
      View.MeasureSpec.makeMeasureSpec(connectorWidth, View.MeasureSpec.EXACTLY)
    val connectorHeightSpec =
      View.MeasureSpec.makeMeasureSpec(totalHeight, View.MeasureSpec.EXACTLY)

    connector!!.measure(connectorWidthSpec, connectorHeightSpec)
    setMeasuredDimension(availableWidth, totalHeight)
  }

  override fun onLayout(
    changed: Boolean,
    l: Int,
    t: Int,
    r: Int,
    b: Int
  ) {
    val width = measuredWidth
    val connectorRight = rowMargins + connector!!.measuredWidth
    connector!!.layout(rowMargins, 0, connectorRight, connector!!.measuredHeight)

    moreButton!!.layout(
        width - rowMargins - moreSize, moreMarginTop, width - rowMargins,
        moreMarginTop + moreSize
    )

    val titleLeft = connectorRight + rowMargins
    val titleBottom = titleMarginTop + title!!.measuredHeight
    title!!.layout(titleLeft, titleMarginTop, titleLeft + title!!.measuredWidth, titleBottom)

    if (details!!.visibility != View.GONE) {
      details!!.layout(
          titleLeft, titleBottom, width - rowMargins,
          titleBottom + details!!.measuredHeight
      )
    }
  }
}
