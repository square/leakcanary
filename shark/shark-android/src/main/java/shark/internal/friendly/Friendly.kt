@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@file:JvmName("shark-android_Friendly")

package shark.internal.friendly

import shark.HeapGraph

internal inline fun HeapGraph.mapNativeSizes() =
  shark.internal.AndroidNativeSizeMapper.mapNativeSizes(this)
