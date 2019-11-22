package com.squareup.leakcanary.deobfuscation

import com.android.build.gradle.api.BaseVariant

/**
 * Extension for the gradle plugin. It allows the user to configure the plugin.
 */
open class LeakCanaryDeobfuscationExtension {

  /**
   * Variant filtering function. It should be overriden to tell LeakCanary which obfuscated
   * variants should be deobfuscated. For example this:
   * ```
   * filterObfuscatedVariants { variant ->
   *   variant.name == "debug"
   * }
   * ```
   * means that debug variant should be deobfuscated by LeakCanary when displaying leaks.
   *
   * If it's not overriden then LeakCanary will
   * use the value of [isMinifyEnabled()](http://tiny.cc/isMinifyEnabled)
   */
  var filterObfuscatedVariants: ((BaseVariant) -> Boolean)? = null
}
