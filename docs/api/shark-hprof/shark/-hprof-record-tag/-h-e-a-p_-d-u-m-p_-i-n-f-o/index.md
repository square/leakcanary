//[shark-hprof](../../../../index.md)/[shark](../../index.md)/[HprofRecordTag](../index.md)/[HEAP_DUMP_INFO](index.md)

# HEAP_DUMP_INFO

[jvm]\
[HEAP_DUMP_INFO](index.md)(254)

Android format addition

Specifies information about which heap certain objects came from. When a sub-tag of this type appears in a HPROF_HEAP_DUMP or HPROF_HEAP_DUMP_SEGMENT record, entries that follow it will be associated with the specified heap.  The HEAP_DUMP_INFO data is reset at the end of the HEAP_DUMP[_SEGMENT].  Multiple HEAP_DUMP_INFO entries may appear in a single HEAP_DUMP[_SEGMENT].

Format: u1: Tag value (0xFE) u4: heap ID ID: heap name string ID

## Properties

| Name | Summary |
|---|---|
| [name](../../-primitive-type/-b-o-o-l-e-a-n/index.md#-372974862%2FProperties%2F219937657) | [jvm]<br>val [name](../../-primitive-type/-b-o-o-l-e-a-n/index.md#-372974862%2FProperties%2F219937657): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ordinal](../../-primitive-type/-b-o-o-l-e-a-n/index.md#-739389684%2FProperties%2F219937657) | [jvm]<br>val [ordinal](../../-primitive-type/-b-o-o-l-e-a-n/index.md#-739389684%2FProperties%2F219937657): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [tag](../tag.md) | [jvm]<br>val [tag](../tag.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
