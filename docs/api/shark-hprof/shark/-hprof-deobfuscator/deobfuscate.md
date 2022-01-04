//[shark-hprof](../../../index.md)/[shark](../index.md)/[HprofDeobfuscator](index.md)/[deobfuscate](deobfuscate.md)

# deobfuscate

[jvm]\
fun [deobfuscate](deobfuscate.md)(proguardMapping: [ProguardMapping](../-proguard-mapping/index.md), inputHprofFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html), outputHprofFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html) = File(
      inputHprofFile.parent, inputHprofFile.name.replace(
      ".hprof", "-deobfuscated.hprof"
    ).let { if (it != inputHprofFile.name) it else inputHprofFile.name + "-deobfuscated" })): [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html)

## See also

jvm

| | |
|---|---|
| [shark.HprofDeobfuscator](index.md) |  |
