package shark.internal

import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.ValueHolder

internal interface FieldValuesReader {
  fun readValue(field: FieldRecord): ValueHolder
}