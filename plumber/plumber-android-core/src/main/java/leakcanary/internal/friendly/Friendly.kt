@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "NOTHING_TO_INLINE")
@file:JvmName("plumber-android_Friendly")

package leakcanary.internal.friendly

import android.os.Handler

internal inline fun checkMainThread() = leakcanary.internal.checkMainThread()

internal inline fun <reified T : Any> noOpDelegate(): T = leakcanary.internal.noOpDelegate()

internal inline val mainHandler: Handler
  get() = leakcanary.internal.mainHandler

internal inline val isMainThread: Boolean
  get() = leakcanary.internal.isMainThread
