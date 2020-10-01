[shark-hprof](../../index.md) / [shark](../index.md) / [HprofRecordTag](index.md) / [HEAP_DUMP_INFO](./-h-e-a-p_-d-u-m-p_-i-n-f-o.md)

# HEAP_DUMP_INFO

`HEAP_DUMP_INFO`

Android format addition

Specifies information about which heap certain objects came from. When a sub-tag of this type
appears in a HPROF_HEAP_DUMP or HPROF_HEAP_DUMP_SEGMENT record, entries that follow it will
be associated with the specified heap.  The HEAP_DUMP_INFO data is reset at the end of the
HEAP_DUMP[_SEGMENT](#).  Multiple HEAP_DUMP_INFO entries may appear in a single
HEAP_DUMP[_SEGMENT](#).

Format: u1: Tag value (0xFE) u4: heap ID ID: heap name string ID

### Inherited Properties

| Name | Summary |
|---|---|
| [tag](tag.md) | `val tag: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
