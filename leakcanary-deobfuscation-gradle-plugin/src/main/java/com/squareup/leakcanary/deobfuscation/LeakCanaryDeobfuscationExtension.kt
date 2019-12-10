/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
