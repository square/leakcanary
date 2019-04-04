package com.squareup.leakcanary

import java.io.Serializable

class Exclusion internal constructor(builder: ExcludedRefs.ParamsBuilder) : Serializable {
  val name: String? = builder.name
  val reason: String? = builder.reason
  val alwaysExclude: Boolean = builder.alwaysExclude
  val matching: String = builder.matching
}
