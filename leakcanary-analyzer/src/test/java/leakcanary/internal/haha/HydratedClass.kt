package leakcanary.internal.haha

import leakcanary.internal.haha.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord

class HydratedClass(
  val record: ClassDumpRecord,
  val className: String,
  val staticFieldNames: List<String>,
  val fieldNames: List<String>
)