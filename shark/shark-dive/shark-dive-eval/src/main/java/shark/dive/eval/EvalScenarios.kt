package shark.dive.eval

import java.io.File
import shark.GcRoot.JniGlobal
import shark.HprofWriterHelper
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder
import shark.dump

/**
 * One heap dump to hand an agent, and the reference an investigation of it has to end on.
 *
 * **The key is known before the tools are asked anything**, which is the whole rule of this eval: either the
 * fixture below writes the leak, so the answer is true by construction, or the dump is one of this
 * repository's real ones and the key is what LeakCanary's own analysis names. Nothing here asks the surface
 * under test what the answer is, and no model decides whether a run got it.
 *
 * A scenario is [about] one thing that is hard, so that a column of results says *which* part of an
 * investigation a change to the method or a refusal moved. See `notes/agent-eval.md`.
 */
class EvalScenario internal constructor(
  val name: String,
  /** The faulty reference, spelled the way `PathReference.leakLabel` spells one: `Holder.activity`. */
  val key: String,
  /** What this dump makes an agent do that the others don't, for the table a run prints. */
  val about: String,
  private val writeHeapDump: (File) -> Unit
) {

  /**
   * Writes this scenario's heap dump into [directory] and answers with the file.
   *
   * Written per run rather than committed, like every other test heap dump in this repository — and rewritten
   * even when it is already there, since a dump left over from an older build of the DSL would be scored as
   * this scenario while being a different one.
   *
   * **Called `heap-dump.hprof` and not [name]**, so the caller gives each scenario a directory of its own: a
   * file called `cache-never-evicts.hprof` hands its answer to whatever opens it, and an agent is answered with
   * the path of what it is reading. See `notes/agent-eval.md`.
   */
  fun writeHeapDumpIn(directory: File): File {
    directory.mkdirs()
    val file = File(directory, HEAP_DUMP_FILE_NAME)
    file.delete()
    writeHeapDump(file)
    return file
  }

  override fun toString(): String = "$name → $key"
}

/**
 * Every scenario an eval run works through.
 *
 * Deliberately few and deliberately different from each other. The families still to add are in
 * `notes/agent-eval.md`, and each of them is a shape a real dump doesn't happen to contain — two candidate
 * references, a loop, a fault in the framework — which is what the synthetic side is for.
 */
object EvalScenarios {

  /**
   * [repositoryRoot] is where the real dumps are read from, since the ones under
   * `shark/shark-android/src/test/resources` are part of this eval and are not ours to rewrite.
   */
  fun all(repositoryRoot: File): List<EvalScenario> = listOf(
    twoApart(),
    aCacheThatNeverEvicts(),
    aRealAsyncTaskLeak(repositoryRoot)
  )

  fun byName(
    name: String,
    repositoryRoot: File
  ): EvalScenario? = all(repositoryRoot).firstOrNull { it.name == name }

  /**
   * The smallest dump that takes an investigation: one unexplained object between the two the heap dump can
   * read for itself.
   *
   * Which is `conclude`'s refusal made real. The application belongs in memory and the activity is destroyed,
   * so a surface that named a faulty reference off the dump alone would answer `App.holder` — and the answer
   * is one step further down, reachable only by someone deciding what the holder is for. A run that fails
   * here fails at the first thing the method asks for.
   */
  private fun twoApart() = EvalScenario(
    name = "two-apart",
    key = "Holder.activity",
    about = "One unexplained step between what belongs in memory and what shouldn't be there"
  ) { file ->
    file.dump {
      androidBuild()
      val activity = destroyedActivity()
      val holder = HOLDER_CLASS_NAME instance { field["activity"] = activity }
      val application = instance(
        clazz(
          className = "com.example.ExampleApplication",
          superclassId = clazz(className = "android.app.Application"),
          fields = listOf("holder" to ReferenceHolder::class)
        ),
        fields = listOf(holder)
      )
      gcRoot(JniGlobal(id = application.value, jniGlobalRefId = 0))
    }
  }

  /**
   * A singleton cache holding a destroyed activity through four steps of infrastructure.
   *
   * The unknown zone is the point: the loader, the cache, its array and the entry all have to be given a
   * verdict before one reference is left, and none of them is anything an inspector knows about. What makes
   * the answer checkable rather than a matter of taste is the static field — `ImageLoader.INSTANCE` holds the
   * loader, so "this is meant to be in memory" is a fact of the dump and not an assumption, and it spreads
   * down to the entry. The activity is watched, so the other end is the app's own word for it.
   *
   * Written bottom up because the loader's class holds the loader: [reserveObjectId] is how an object points
   * at something written after it.
   */
  private fun aCacheThatNeverEvicts() = EvalScenario(
    name = "cache-never-evicts",
    key = "CacheEntry.activity",
    about = "Four steps of infrastructure with no verdict, rooted at a static singleton"
  ) { file ->
    file.dump {
      androidBuild()
      val loader = reserveObjectId()
      val activity = destroyedActivity()
      // The app's own record that it is done with this activity, which the method says to start from.
      keyedWeakReference(activity)
      val entry = CACHE_ENTRY_CLASS_NAME instance {
        field["key"] = string("screen:main")
        field["activity"] = activity
      }
      val entries = objectArray(entry)
      val cache = "com.example.image.MemoryCache" instance {
        field["entries"] = entries
        field["size"] = IntHolder(1)
      }
      instance(
        clazz(
          className = "com.example.image.ImageLoader",
          // A class is a GC root of its own, so this static field is what roots the whole chain — and it is
          // what an agent can point at to defend a verdict on everything below it.
          staticFields = listOf("INSTANCE" to loader),
          fields = listOf("cache" to ReferenceHolder::class)
        ),
        fields = listOf(cache),
        objectId = loader
      )
    }
  }

  /**
   * A real Android heap dump of a real leak, which is the one scenario nothing about this repository invented.
   *
   * `leak_asynctask_o.hprof` is the dump `LegacyHprofTest` pins the leaking object and the retained size of,
   * so the key is checked against the library's own reading rather than against ours: an anonymous
   * `AsyncTask` subclass holding the activity that declared it, through the field the compiler generates for
   * exactly that. Copied into the run's directory rather than opened where it lies, so that an eval run
   * writes nothing into the repository — an agent sets verdicts and notes as it works, and those land beside
   * the dump.
   */
  private fun aRealAsyncTaskLeak(repositoryRoot: File) = EvalScenario(
    name = "real-asynctask",
    key = "MainActivity\$2.this\$0",
    about = "A real dump: 8 MB, an inner class, and a chain nobody wrote for this eval"
  ) { file ->
    val real = File(repositoryRoot, REAL_ASYNC_TASK_DUMP)
    require(real.isFile) {
      "There is no heap dump at ${real.absolutePath}. The real-dump scenarios are read out of this " +
        "repository, so an eval run has to say where it is: pass the repository root."
    }
    real.copyTo(file, overwrite = true)
  }
}

/**
 * An instance of the app's own `Activity` subclass whose inherited `mDestroyed` is true, which is what the
 * object inspectors read to say an object shouldn't be in memory.
 *
 * Field values are written most derived class first, and the subclass declares none, so the instance is
 * written with the one field its superclass has.
 */
private fun HprofWriterHelper.destroyedActivity(): ReferenceHolder = instance(
  clazz(
    className = "com.example.MainActivity",
    superclassId = clazz(
      className = "android.app.Activity",
      fields = listOf("mDestroyed" to BooleanHolder::class)
    )
  ),
  fields = listOf(BooleanHolder(true))
)

/**
 * What `android.os.Build` looks like in a dump, which is what Shark matches its library leak patterns
 * against — and a dump with the class but not these three fields makes it throw a bare NPE from under
 * everything. See `shark/shark-dive/AGENTS.md`.
 *
 * Duplicated from the other modules' tests rather than shared, since a test helper is not worth a module's
 * public API.
 */
private fun HprofWriterHelper.androidBuild() {
  "android.os.Build" clazz {
    staticField["MANUFACTURER"] = string("Google")
    staticField["ID"] = string("BP31.250610.004")
  }
  "android.os.Build\$VERSION" clazz {
    // Recent enough that none of Shark's known library leaks is in these dumps, so the references a chain
    // names are the app's own — a library leak is a scenario of its own, not a surprise in another one.
    staticField["SDK_INT"] = IntHolder(34)
  }
}

/** What every scenario's dump is called, whichever scenario it is. See [EvalScenario.writeHeapDumpIn]. */
const val HEAP_DUMP_FILE_NAME = "heap-dump.hprof"

private const val HOLDER_CLASS_NAME = "com.example.Holder"

private const val CACHE_ENTRY_CLASS_NAME = "com.example.image.CacheEntry"

/** Where the real dump lives, which is a test resource of `shark-android` and stays one. */
private const val REAL_ASYNC_TASK_DUMP = "shark/shark-android/src/test/resources/leak_asynctask_o.hprof"
