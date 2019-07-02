package leakcanary

import leakcanary.GraphObjectRecord.GraphClassRecord

class GraphField(
  val classRecord: GraphClassRecord,
  val name: String,
  val value: GraphHeapValue
)