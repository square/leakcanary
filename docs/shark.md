# Shark 🦈

<!-- Made with http://patorjk.com/text-color-fader/ -->
**Shark**: **<span style="color:#c757bc;">S</span><span style="color:#c858b7;">m</span><span style="color:#ca5ab2;">a</span><span style="color:#cb5bad;">r</span><span style="color:#cc5ca9;">t</span><span style="color:#ce5ea4;"> </span><span style="color:#cf5f9f;">H</span><span style="color:#d0609a;">e</span><span style="color:#d26295;">a</span><span style="color:#d36390;">p</span><span style="color:#d4658c;"> </span><span style="color:#d66687;">A</span><span style="color:#d76782;">n</span><span style="color:#d8697d;">a</span><span style="color:#da6a78;">l</span><span style="color:#db6b73;">y</span><span style="color:#dc6d6f;">s</span><span style="color:#de6e6a;">i</span><span style="color:#df6f65;">s</span><span style="color:#e07160;"> </span><span style="color:#e1725b;">R</span><span style="color:#e37356;">e</span><span style="color:#e47552;">p</span><span style="color:#e5764d;">o</span><span style="color:#e77748;">r</span><span style="color:#e87943;">t</span><span style="color:#e97a3e;">s</span><span style="color:#eb7b39;"> </span><span style="color:#ec7d35;">f</span><span style="color:#ed7e30;">o</span><span style="color:#ef802b;">r</span><span style="color:#f08126;"> </span><span style="color:#f18221;">K</span><span style="color:#f3841c;">o</span><span style="color:#f48518;">t</span><span style="color:#f58613;">l</span><span style="color:#f7880e;">i</span><span style="color:#f88909;">n</span>**

<p align="center">
<img src="https://github.com/square/leakcanary/wiki/assets/shark.png" />
</p>

Shark is the heap analyzer that powers LeakCanary. It's a standalone heap analyzer Kotlin library that can run in Java and Android VMs at **high speed** with a **low memory footprint**. It can analyze both Android and Java VM hprof files.

Shark is released as several distinct libraries:

* `Shark Hprof`: Read and write records in hprof files
* `Shark Graph`: Navigate the heap object graph
* `Shark`: Generate heap analysis reports
* `Shark Android`: Generate Android tailored heap analysis reports

Shark is also released as a CLI tool, `Shark CLI`.

## Example usage

### Reading records in a hprof file with shark-hprof

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

### Navigating the heap object graph with shark-graph

```kotlin
// Prints all thread names
Hprof.open(heapDumpFile)
    .use { hprof ->
      val heapGraph = HeapGraph.indexHprof(hprof)
      val threadClass = heapGraph.findClassByName("java.lang.Thread")!!
      val threadNames: Sequence<String> = threadClass.instances.map { instance ->
        val nameField = instance["java.lang.Thread", "name"]!!
        nameField.value.readAsJavaString()!!
      }
      threadNames.forEach { println(it) }
    }
```

### Generating a heap analysis report with shark

```kotlin
val heapAnalyzer = HeapAnalyzer(AnalyzerProgressListener.NONE)
val analysis = heapAnalyzer.checkForLeaks(
    heapDumpFile = heapDumpFile,
    leakFinders = listOf(ObjectInspector { _, reporter ->
      reporter.whenInstanceOf("com.example.ThingWithLifecycle") { instance ->
        val field = instance["com.example.ThingWithLifecycle", "destroyed"]!!
        val destroyed = field.value.asBoolean!!
        if (destroyed) {
          reportLeaking(reason = "ThingWithLifecycle.destroyed = true")
        }
      }
    })
)
println(analysis)
```

### Generating an Android heap analysis report with shark-android


```kotlin
val heapAnalyzer = HeapAnalyzer(AnalyzerProgressListener.NONE)
val analysis = heapAnalyzer.checkForLeaks(
    heapDumpFile = heapDumpFile,
    referenceMatchers = AndroidReferenceMatchers.appDefaults,
    objectInspectors = AndroidObjectInspectors.appDefaults
)
println(analysis)
```
