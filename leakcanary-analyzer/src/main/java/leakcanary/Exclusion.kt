package leakcanary

import leakcanary.ExcludedRefs.ParamsBuilder
import java.io.Serializable

class Exclusion internal constructor(builder: ParamsBuilder) : Serializable {
  val name: String? = builder.name
  val reason: String? = builder.reason
  val alwaysExclude: Boolean = builder.alwaysExclude
  val matching: String = builder.matching
}
