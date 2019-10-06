package com.squareup.leakcanary.leak.deobfuscation.plugin

open class LeakCanaryDeobfuscationExtension {
  var obfuscatedVariantNames: Collection<String>? = null

  fun obfuscatedVariantNames(vararg names: String) {
    obfuscatedVariantNames = names.toList()
  }
}
