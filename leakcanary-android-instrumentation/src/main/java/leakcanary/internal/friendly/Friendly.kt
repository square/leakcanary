@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@file:JvmName("leakcanary-android-instrumentation_Friendly")
package leakcanary.internal.friendly

internal inline fun <reified T : Any> noOpDelegate(): T = leakcanary.internal.noOpDelegate()