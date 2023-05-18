package org.leakcanary.internal

import android.os.Parcel
import android.os.Parcelable
import shark.HeapAnalysis

class ParcelableHeapAnalysis(val wrapped: HeapAnalysis) : Parcelable {

  private constructor(source: Parcel) : this(source.readSerializable() as HeapAnalysis)

  override fun writeToParcel(dest: Parcel, flags: Int) {
    dest.writeSerializable(wrapped)
  }

  override fun describeContents() = 0

  companion object {
    @Suppress("UNCHECKED_CAST")
    @JvmField val CREATOR = object : Parcelable.Creator<ParcelableHeapAnalysis> {
      override fun createFromParcel(source: Parcel): ParcelableHeapAnalysis {
        return ParcelableHeapAnalysis(source)
      }

      override fun newArray(size: Int): Array<ParcelableHeapAnalysis?> {
        return arrayOfNulls(size)
      }
    }

    fun HeapAnalysis.asParcelable(): ParcelableHeapAnalysis = ParcelableHeapAnalysis(this)
  }
}
