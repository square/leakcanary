package leakcanary.internal.activity.ui

import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.annotation.LayoutRes
import leakcanary.internal.navigation.inflate

class SimpleListAdapter<T>(
  @LayoutRes
  private val rowResId: Int,
  private val items: List<T>,
  private val bindView: SimpleListAdapter<T>.(View, Int) -> Unit
) : BaseAdapter() {
  override fun getView(
    position: Int,
    convertView: View?,
    parent: ViewGroup
  ): View {
    val view = convertView ?: parent.inflate(rowResId)
    bindView(view, position)
    return view
  }

  override fun getItem(position: Int) = items[position]

  override fun getItemId(position: Int) = position.toLong()

  override fun getCount() = items.size
}