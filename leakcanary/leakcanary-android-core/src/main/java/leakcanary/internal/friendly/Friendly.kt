@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "NOTHING_TO_INLINE")
@file:JvmName("leakcanary-android-core_Friendly")

package leakcanary.internal.friendly

internal inline val mainHandler
  get() = leakcanary.internal.mainHandler

internal inline fun checkMainThread() = leakcanary.internal.checkMainThread()

internal inline fun checkNotMainThread() = leakcanary.internal.checkNotMainThread()

internal inline fun <reified T : Any> noOpDelegate(): T = leakcanary.internal.noOpDelegate()

internal inline fun measureDurationMillis(block: () -> Unit) =
  leakcanary.internal.measureDurationMillis(block)
