# Shark 🦈

<!-- Made with http://patorjk.com/text-color-fader/ -->
**Shark**: **<span style="color:#c757bc;">S</span><span style="color:#c858b7;">m</span><span style="color:#ca5ab2;">a</span><span style="color:#cb5bad;">r</span><span style="color:#cc5ca9;">t</span><span style="color:#ce5ea4;"> </span><span style="color:#cf5f9f;">H</span><span style="color:#d0609a;">e</span><span style="color:#d26295;">a</span><span style="color:#d36390;">p</span><span style="color:#d4658c;"> </span><span style="color:#d66687;">A</span><span style="color:#d76782;">n</span><span style="color:#d8697d;">a</span><span style="color:#da6a78;">l</span><span style="color:#db6b73;">y</span><span style="color:#dc6d6f;">s</span><span style="color:#de6e6a;">i</span><span style="color:#df6f65;">s</span><span style="color:#e07160;"> </span><span style="color:#e1725b;">R</span><span style="color:#e37356;">e</span><span style="color:#e47552;">p</span><span style="color:#e5764d;">o</span><span style="color:#e77748;">r</span><span style="color:#e87943;">t</span><span style="color:#e97a3e;">s</span><span style="color:#eb7b39;"> </span><span style="color:#ec7d35;">f</span><span style="color:#ed7e30;">o</span><span style="color:#ef802b;">r</span><span style="color:#f08126;"> </span><span style="color:#f18221;">K</span><span style="color:#f3841c;">o</span><span style="color:#f48518;">t</span><span style="color:#f58613;">l</span><span style="color:#f7880e;">i</span><span style="color:#f88909;">n</span>**

<p align="center">
<img src="../images/shark.png" />
</p>

Shark is the heap analyzer that powers LeakCanary 2. It's a Kotlin standalone heap analysis library that runs at **high speed** with a **low memory footprint**.

Shark is released in layers:

1. **Shark Hprof**: Read and write records in hprof files.
2. **Shark Graph**: Navigate the heap object graph.
3. **Shark**: Generate heap analysis reports.
4. **Shark Android**: Android heuristics to generate tailored heap analysis reports.
5. **Shark CLI**: Analyze the heap of debuggable apps installed on an Android device connected to your desktop. The output is similar to the output of LeakCanary, except you don't have to add the LeakCanary dependency to your app.
6. **LeakCanary**: Builds on top. It automatically watches destroyed activities and fragments, triggers a heap dump, runs Shark Android and then displays the result.

A few more things:

* Shark is built on top of Okio. Okio makes it easy to parse heap dumps efficiently.
* Shark is a 100% Kotlin library, and Kotlin is essential to its design, because Shark relies heavily on sealed classes and sequences to save memory.
* Shark has the unique ability to help narrow down the cause of memory leaks through platform specific [heuristics](fundamentals.md#heuristics-and-labels).
* Shark is heavily tested (80% test coverage).
* Shark can run in both Java and Android VMs, with no other dependency than Okio and Kotlin.
* Shark can analyze both Java and Android VM hprof files.
* Shark can deobfuscate hprof records if it has access to obfuscation mapping file.

## Shark CLI

The Shark Command Line Interface (CLI) enables you to analyze heaps directly from your computer. It can dump the heap of an app installed on a connected Android device, analyze it, and even strip a heap dump of any sensitive data (e.g. PII, passwords or encryption keys) which is useful when sharing a heap dump.

Download it [here](https://github.com/square/leakcanary/releases/download/v{{ leak_canary.release }}/shark-cli-{{ leak_canary.release }}.zip)!

Usage instructions:

```
$ ./bin/shark-cli

Usage: shark-cli [OPTIONS] COMMAND [ARGS]...

                   ^`.                 .=""=.
   ^_              \  \               / _  _ \
   \ \             {   \             |  d  b  |
   {  \           /     `~~~--__     \   /\   /
   {   \___----~~'              `~~-_/'-=\/=-'\,
    \                         /// a  `~.      \ \
    / /~~~~-, ,__.    ,      ///  __,,,,)      \ |
    \/      \/    `~~~;   ,---~~-_`/ \        / \/
                     /   /            '.    .'
                    '._.'             _|`~~`|_
                                      /|\  /|\

Options:
  -h, --help  Show this message and exit

Commands:
  analyze-process  Dumps the heap for the provided partial PROCESS_NAME, pulls
                   the hprof file and analyzes it. (Usage: shark-cli
                   analyze-process [--device ID --obfuscation-mapping PATH]
                   PROCESS_NAME)
  dump-process     Dumps the heap for the provided partial PROCESS_NAME and
                   pulls the hprof file. (Usage: shark-cli dump-process
                   [--device ID] PROCESS_NAME)
  analyze-hprof    Analyzes the provided HPROF_FILE_PATH. (Usage: shark-cli
                   analyze-hprof [--obfuscation-mapping PATH] HPROF_FILE_PATH)
  strip-hprof      Replaces all primitive arrays from the provided
                   HPROF_FILE_PATH with arrays of zeroes and generates a new
                   "-stripped.hprof" file. (Usage: shark-cli strip-hprof
                   HPROF_FILE_PATH)
```

## Shark code examples

### Reading records in a hprof file

```groovy
dependencies {
  implementation 'com.squareup.leakcanary:shark-hprof:$sharkVersion'
}
```

```kotlin
// Prints all class and field names
Hprof.open(heapDumpFile)
    .use { hprof ->
      hprof.reader.readHprofRecords(
          recordTypes = setOf(StringRecord::class),
          listener = OnHprofRecordListener { position, record ->
            println((record as StringRecord).string)
          })
    }
```

### Navigating the heap object graph

```groovy
dependencies {
  implementation 'com.squareup.leakcanary:shark-graph:$sharkVersion'
}
```

```kotlin
// Prints all thread names
Hprof.open(heapDumpFile)
    .use { hprof ->
      val heapGraph = HprofHeapGraph.indexHprof(hprof)
      val threadClass = heapGraph.findClassByName("java.lang.Thread")!!
      val threadNames: Sequence<String> = threadClass.instances.map { instance ->
        val nameField = instance["java.lang.Thread", "name"]!!
        nameField.value.readAsJavaString()!!
      }
      threadNames.forEach { println(it) }
    }
```

### Generating a heap analysis report

```groovy
dependencies {
  implementation 'com.squareup.leakcanary:shark:$sharkVersion'
}
```

```kotlin
val heapAnalyzer = HeapAnalyzer(AnalyzerProgressListener.NONE)
val analysis = heapAnalyzer.checkForLeaks(
    heapDumpFile = heapDumpFile,
    leakFinders = listOf(ObjectInspector { _, reporter ->
      reporter.whenInstanceOf("com.example.ThingWithLifecycle") { instance ->
        val field = instance["com.example.ThingWithLifecycle", "destroyed"]!!
        val destroyed = field.value.asBoolean!!
        if (destroyed) {
          leakingReasons += "ThingWithLifecycle.destroyed = true"
        }
      }
    })
)
println(analysis)
```

### Generating an Android heap analysis report

```groovy
dependencies {
  implementation 'com.squareup.leakcanary:shark-android:$sharkVersion'
}
```


```kotlin
val heapAnalyzer = HeapAnalyzer(AnalyzerProgressListener.NONE)
val analysis = heapAnalyzer.checkForLeaks(
    heapDumpFile = heapDumpFile,
    referenceMatchers = AndroidReferenceMatchers.appDefaults,
    objectInspectors = AndroidObjectInspectors.appDefaults
)
println(analysis)
```
