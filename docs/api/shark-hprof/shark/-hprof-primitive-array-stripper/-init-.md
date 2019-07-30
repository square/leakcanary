[shark-hprof](../../index.md) / [shark](../index.md) / [HprofPrimitiveArrayStripper](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`HprofPrimitiveArrayStripper()`

Converts a Hprof file to another file with all primitive arrays replaced with arrays of zeroes,
which can be useful to remove PII. Char arrays are handled slightly differently because 0 would
be the null character so instead these become arrays of '?'.

