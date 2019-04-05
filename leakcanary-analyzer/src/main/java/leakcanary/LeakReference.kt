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
package leakcanary

import leakcanary.LeakTraceElement.Type
import leakcanary.LeakTraceElement.Type.ARRAY_ENTRY
import leakcanary.LeakTraceElement.Type.INSTANCE_FIELD
import leakcanary.LeakTraceElement.Type.LOCAL
import leakcanary.LeakTraceElement.Type.STATIC_FIELD
import java.io.Serializable

/**
 * A single field in a [LeakTraceElement].
 */
class LeakReference(
  val type: Type,
  val name: String?,
  val value: String?
) : Serializable {

  val displayName: String
    get() {
      return when (type) {
        ARRAY_ENTRY -> "[$name]"
        STATIC_FIELD, INSTANCE_FIELD -> name!!
        LOCAL -> "<Java Local>"
      }
    }

  override fun toString(): String {
    return when (type) {
      ARRAY_ENTRY, INSTANCE_FIELD -> "$displayName = $value"
      STATIC_FIELD -> "static $displayName = $value"
      LOCAL -> displayName
    }
  }
}
