

### All Types

| Name | Summary |
|---|---|
| [shark.GcRoot](../shark/-gc-root/index.md) | A GcRoot as identified by [HprofRecord.HeapDumpRecord.GcRootRecord](../shark/-hprof-record/-heap-dump-record/-gc-root-record/index.md) in the heap dump. |
| [shark.Hprof](../shark/-hprof/index.md) | An opened Hprof file which can be read via [reader](../shark/-hprof/reader.md). Open a new hprof with [open](../shark/-hprof/open.md), and don't forget to call [close](../shark/-hprof/close.md) once done. |
| [shark.HprofPrimitiveArrayStripper](../shark/-hprof-primitive-array-stripper/index.md) | Converts a Hprof file to another file with all primitive arrays replaced with arrays of zeroes, which can be useful to remove PII. Char arrays are handled slightly differently because 0 would be the null character so instead these become arrays of '?'. |
| [shark.HprofReader](../shark/-hprof-reader/index.md) | Reads hprof content from an Okio [BufferedSource](#). |
| [shark.HprofRecord](../shark/-hprof-record/index.md) | A Hprof record. These data structure map 1:1 with how records are written in hprof files. |
| [shark.HprofWriter](../shark/-hprof-writer/index.md) | Generates Hprof files. |
| [shark.OnHprofRecordListener](../shark/-on-hprof-record-listener/index.md) | Listener passed in to [HprofReader.readHprofRecords](../shark/-hprof-reader/read-hprof-records.md), gets notified for each [HprofRecord](../shark/-hprof-record/index.md) found in the heap dump which types is in the set of the recordTypes parameter passed to [HprofReader.readHprofRecords](../shark/-hprof-reader/read-hprof-records.md). |
| [shark.PrimitiveType](../shark/-primitive-type/index.md) | A primitive type in the prof. |
| [shark.ValueHolder](../shark/-value-holder/index.md) | A value in the heap dump, which can be a [ReferenceHolder](../shark/-value-holder/-reference-holder/index.md) or a primitive type. |
