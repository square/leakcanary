package leakcanary.internal

import leakcanary.HeapValue
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord

internal interface FieldValuesReader {
  fun readValue(field: FieldRecord): HeapValue
}