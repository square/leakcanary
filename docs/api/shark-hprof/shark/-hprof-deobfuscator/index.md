//[shark-hprof](../../../index.md)/[shark](../index.md)/[HprofDeobfuscator](index.md)

# HprofDeobfuscator

[jvm]\
class [HprofDeobfuscator](index.md)

Converts a Hprof file to another file with deobfuscated class and field names.

## Constructors

| | |
|---|---|
| [HprofDeobfuscator](-hprof-deobfuscator.md) | [jvm]<br>fun [HprofDeobfuscator](-hprof-deobfuscator.md)() |

## Functions

| Name | Summary |
|---|---|
| [deobfuscate](deobfuscate.md) | [jvm]<br>fun [deobfuscate](deobfuscate.md)(proguardMapping: [ProguardMapping](../-proguard-mapping/index.md), inputHprofFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html), outputHprofFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html) = File(       inputHprofFile.parent, inputHprofFile.name.replace(       ".hprof", "-deobfuscated.hprof"     ).let { if (it != inputHprofFile.name) it else inputHprofFile.name + "-deobfuscated" })): [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html) |
