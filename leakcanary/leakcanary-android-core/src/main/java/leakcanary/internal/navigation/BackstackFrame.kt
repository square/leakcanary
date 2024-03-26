package leakcanary.internal.navigation

import android.os.Parcel
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import com.squareup.leakcanary.core.R

internal class BackstackFrame : Parcelable {

  val screen: Screen
  private val viewState: SparseArray<Parcelable>?

  private constructor(
    source: Parcel
  ) {
    this.screen = source.readSerializable() as Screen
    @Suppress("UNCHECKED_CAST")
    this.viewState = source.readSparseArray(javaClass.classLoader)
  }

  constructor(
    screen: Screen
  ) {
    this.screen = screen
    viewState = null
  }

  constructor(
    screen: Screen,
    view: View
  ) {
    this.screen = screen
    viewState = SparseArray()
    view.saveHierarchyState(viewState)
    view.setTag(R.id.leak_canary_restored_view_state, null)
  }

  fun restore(view: View) {
    if (viewState != null) {
      view.restoreHierarchyState(viewState)
      view.setTag(R.id.leak_canary_restored_view_state, viewState)
    } else {
      view.setTag(R.id.leak_canary_restored_view_state, null)
    }
  }

  override fun describeContents() = 0

  @Suppress("UNCHECKED_CAST")
  override fun writeToParcel(
    dest: Parcel,
    flags: Int
  ) {
    dest.writeSerializable(screen)
    dest.writeSparseArray(viewState as SparseArray<Any>?)
  }

  companion object {
    @Suppress("UNCHECKED_CAST")
    @JvmField val CREATOR = object : Parcelable.Creator<BackstackFrame> {
      override fun createFromParcel(source: Parcel): BackstackFrame {
        return BackstackFrame(source)
      }

      override fun newArray(size: Int): Array<BackstackFrame?> {
        return arrayOfNulls(size)
      }
    }
  }
}
