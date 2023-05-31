package org.leakcanary.internal

import android.os.Parcel
import android.os.Parcelable
import shark.Dominators
import shark.HeapAnalysis

class ParcelableDominators(val wrapped: Dominators) : Parcelable {

  private constructor(source: Parcel) : this(source.readSerializable() as Dominators)

  override fun writeToParcel(dest: Parcel, flags: Int) {
    dest.writeSerializable(wrapped)
    println("FOO WRITE ${dest.dataSize()} $dest")
  }

  override fun describeContents() = 0

  companion object {
    @Suppress("UNCHECKED_CAST")
    @JvmField val CREATOR = object : Parcelable.Creator<ParcelableDominators> {
      override fun createFromParcel(source: Parcel): ParcelableDominators {
        println("FOO READ ${source.dataSize()} $source")
        return ParcelableDominators(source)
      }

      override fun newArray(size: Int): Array<ParcelableDominators?> {
        return arrayOfNulls(size)
      }
    }

    fun Dominators.asParcelable(): ParcelableDominators = ParcelableDominators(this)
  }
}
