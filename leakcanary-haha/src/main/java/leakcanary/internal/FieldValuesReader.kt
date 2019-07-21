package leakcanary.internal

import leakcanary.ValueHolder
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord

internal interface FieldValuesReader {
  fun readValue(field: FieldRecord): ValueHolder
}