[shark-hprof](../../index.md) / [shark](../index.md) / [HprofDeobfuscator](./index.md)

# HprofDeobfuscator

`class HprofDeobfuscator`

Converts a Hprof file to another file with deobfuscated class and field names.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `HprofDeobfuscator()`<br>Converts a Hprof file to another file with deobfuscated class and field names. |

### Functions

| Name | Summary |
|---|---|
| [deobfuscate](deobfuscate.md) | `fun deobfuscate(proguardMapping: `[`ProguardMapping`](../-proguard-mapping/index.md)`, inputHprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, outputHprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)` = File(
        inputHprofFile.parent, inputHprofFile.name.replace(
        ".hprof", "-deobfuscated.hprof"
    ).let { if (it != inputHprofFile.name) it else inputHprofFile.name + "-deobfuscated" })): `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html) |
