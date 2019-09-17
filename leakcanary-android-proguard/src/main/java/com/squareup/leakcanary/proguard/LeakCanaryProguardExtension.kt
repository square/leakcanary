package com.squareup.leakcanary.proguard

open class LeakCanaryProguardExtension {
  var obfuscatedVariantNames: Collection<String>? = null

  fun obfuscatedVariantNames(vararg names: String) {
    obfuscatedVariantNames = names.toList()
  }
}
