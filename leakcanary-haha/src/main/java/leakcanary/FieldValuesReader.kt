package leakcanary

import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord

interface FieldValuesReader {
  fun readValue(field: FieldRecord): HeapValue
}