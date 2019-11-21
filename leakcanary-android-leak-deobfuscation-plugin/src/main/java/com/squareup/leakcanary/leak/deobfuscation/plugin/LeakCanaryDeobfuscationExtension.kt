package com.squareup.leakcanary.leak.deobfuscation.plugin

import com.android.build.gradle.api.BaseVariant

open class LeakCanaryDeobfuscationExtension {
  var filterObfuscatedVariants: (BaseVariant) -> Boolean = { false }
}
