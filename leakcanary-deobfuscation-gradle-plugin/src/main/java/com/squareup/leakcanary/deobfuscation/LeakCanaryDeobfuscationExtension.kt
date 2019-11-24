package com.squareup.leakcanary.deobfuscation

import com.android.build.gradle.api.BaseVariant

/**
 * Extension for the gradle plugin. It allows the user to configure the plugin.
 */
open class LeakCanaryDeobfuscationExtension {

  /**
   * Variant filtering function. It should be overriden to tell LeakCanary for which obfuscated
   * variants it should copy the mapping file into apk. For example this:
   * ```
   * filterObfuscatedVariants { variant ->
   *   variant.name == "debug"
   * }
   * ```
   * means that debug variant should be deobfuscated by LeakCanary when displaying leaks.
   *
   * Default value is *false* so no variant will have the obfuscation mapping file copied.
   */
  var filterObfuscatedVariants: (BaseVariant) -> Boolean = { false }
}
