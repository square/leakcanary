package shark.dive.eval

import java.io.File
import shark.GcRoot.JniGlobal
import shark.HprofWriterHelper
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder
import shark.dive.LeakStatus
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
  /**
   * The verdicts it takes to close this scenario's unknown zone, by the class name of the object each one
   * is set on — a fixture knows the classes it wrote and not the addresses they land on.
   *
   * **Empty for a scenario the heap dump already supplies one end of**, which is most of them: a watched
   * destroyed activity at the bottom is the dump's own `STUCK`, so the only verdict a run has to add is an
   * `EXPECTED` above the unknown zone, and `EvalScenariosTest` sets that one without being told where. Spell
   * these out when that isn't true — when the dump's own reading is at neither end of what a run has to
   * decide, and no rule could guess which object the missing verdict goes on. `stub-outlives-its-work` is
   * the one: the dump reads both ends for itself, a watched activity at the bottom and a binder stub at the
   * top, and what a run has to add is the `STUCK` in the middle.
   */
  internal val solvedBy: Map<String, LeakStatus> = emptyMap(),
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
    aStubThatOutlivesItsWork(),
    aStubHoldingSomethingWithNoStateOfItsOwn(),
    aRealAsyncTaskLeak(repositoryRoot)
  )

  fun byName(
    name: String,
    repositoryRoot: File
  ): EvalScenario? = all(repositoryRoot).firstOrNull { it.name == name }

  /**
   * The smallest dump that takes an investigation: one object with no verdict between the two the heap dump
   * can read for itself.
   *
   * Which is what an unsolved leak is made of. The application belongs in memory and the activity is watched and
   * destroyed, so what is left between them is **two** references — `ExampleApplication.settings` and
   * `SettingsStore.context` — and a surface that named one of them off the dump alone would be guessing
   * between them. What decides is the verdict on the one object in between, and the dump carries the evidence
   * for it: [SETTINGS_STORE_CLASS_NAME] has three writes outstanding, so its own work is not finished and it
   * belongs in memory, which leaves the reference below it as the only candidate. A run that fails here fails
   * at the first thing the method asks for.
   *
   * **It used to be a `Holder` whose only field was the activity, and that was not a fair scenario.** An
   * object whose whole job is holding a destroyed activity reads as done with its work, so the dump's own
   * evidence pointed at `ExampleApplication.holder` while the key said `Holder.activity` — and two runs that
   * did everything the method asks answered the first. A scenario whose answer is the author's intention
   * rather than the dump's content measures nothing, whichever way the score comes out.
   */
  private fun twoApart() = EvalScenario(
    name = "two-apart",
    key = "SettingsStore.context",
    about = "One object with no verdict between what belongs in memory and what shouldn't be there"
  ) { file ->
    file.dump {
      androidBuild()
      val activity = destroyedActivity()
      // The app's own record that it is done with this activity, which is the bottom end of the unknown zone.
      keyedWeakReference(activity)
      val settings = SETTINGS_STORE_CLASS_NAME instance {
        // An activity where the application context was wanted, which is the leak and a root cause somebody
        // can act on: this object outlives every screen, so the context it is built with has to as well.
        field["context"] = activity
        // And what says this object's work is not done, which is the one verdict a run has to defend.
        field["pendingWrites"] = IntHolder(3)
      }
      val application = instance(
        clazz(
          className = "com.example.ExampleApplication",
          superclassId = clazz(className = "android.app.Application"),
          fields = listOf("settings" to ReferenceHolder::class)
        ),
        fields = listOf(settings)
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
          // A class is a GC root of its own, so this static field is what roots the whole path — and it is
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
   * A binder stub that is meant to be in memory, holding a request that has already finished.
   *
   * The one scenario where the two ends the dump supplies are the wrong way round for the shortest reading
   * of the method. What shouldn't be there is at the bottom as usual — a watched destroyed activity — but
   * the only object anybody can defend as belonging in memory is at the very *top*, and it is a binder stub:
   * another process holds a proxy to it, so when it goes is not this process's decision and there is nothing
   * to fix about it being here. An investigation that reads that as licence for what the stub *holds* has the
   * rule backwards, and comes out with `EXPECTED` all the way down and the last reference on the path as its
   * answer. The rule only runs the one way: a holder of something expected is expected, never the held.
   *
   * So the verdict that costs something is the `STUCK` on [UPLOAD_CALLBACKS_CLASS_NAME], and it is the only
   * one a run has to add: `AndroidObjectInspectors.STUB` reports the stub itself as not leaking, for the
   * reason above, which is what puts the top end of the unknown zone there without anybody arguing for it.
   * The dump carries the evidence for the other end rather than asking for taste — `delivered` is true, so
   * the result this object exists to receive has been received and nothing should still be pointing at it.
   * What the stub points at with the field a compiler wrote for it is then the leak.
   *
   * [UPLOAD_BINDING_CLASS_NAME] is the control, and the reason a run can't score here by refusing whatever
   * sits under a stub: a second stub of the same dump, rooted by a JNI global reference the same way, that is
   * nothing to fix. Static, so there is no field a compiler wrote, and what it holds is a service that is
   * running. Deciding between the two is a matter of reading what is around each of them.
   */
  private fun aStubThatOutlivesItsWork() = EvalScenario(
    name = "stub-outlives-its-work",
    key = "UploadCallbacks\$ResultStub.this\$0",
    about = "The one object that belongs in memory is a binder stub at the top, and a verdict spreads up",
    solvedBy = mapOf(UPLOAD_CALLBACKS_CLASS_NAME to LeakStatus.STUCK)
  ) { file ->
    file.dump {
      androidBuild()
      // Declared once and subclassed twice, the way a dump of a real process has it: what makes an object a
      // stub is its superclass, which is what `AndroidObjectInspectors.STUB` matches on.
      val binder = clazz(className = "android.os.Binder")
      val activity = destroyedActivity()
      keyedWeakReference(activity)
      val controller = "com.example.upload.UploadController" instance {
        field["activity"] = activity
        field["requestId"] = string("upload-4d1c")
      }
      val callbacks = instance(
        clazz(
          className = UPLOAD_CALLBACKS_CLASS_NAME,
          fields = listOf(
            // The result this object exists to receive, already received. Which is what a run has to point
            // at to defend the one verdict this scenario is about.
            "delivered" to BooleanHolder::class,
            "controller" to ReferenceHolder::class
          )
        ),
        fields = listOf(BooleanHolder(true), controller)
      )
      val stub = instance(
        clazz(
          className = RESULT_STUB_CLASS_NAME,
          superclassId = binder,
          fields = listOf("this\$0" to ReferenceHolder::class)
        ),
        fields = listOf(callbacks)
      )
      // A JNI global reference on a binder is the other process holding a proxy to it, which is the whole of
      // why a stub outliving its work is nothing this process did wrong.
      gcRoot(JniGlobal(id = stub.value, jniGlobalRefId = 0))

      val service = "com.example.upload.UploadService" instance {
        field["started"] = BooleanHolder(true)
      }
      val binding = instance(
        clazz(
          className = UPLOAD_BINDING_CLASS_NAME,
          superclassId = binder,
          fields = listOf("service" to ReferenceHolder::class)
        ),
        fields = listOf(service)
      )
      gcRoot(JniGlobal(id = binding.value, jniGlobalRefId = 1))
    }
  }

  /**
   * The same inversion as [aStubThatOutlivesItsWork], with the evidence moved off the object the verdict
   * goes on — which is the half of that scenario a run can pass without doing.
   *
   * `stub-outlives-its-work` writes `delivered = true` onto the very object a run has to call `STUCK`, so a
   * run scores there by reading one field of one object, and an investigation that asks "does this object
   * have a field that says done?" is indistinguishable from one that asks "is this object's work done?".
   * Those come apart on a real dump, where the object under a stub is usually a dispatcher with no state at
   * all — a `ResultReceiver` subclass whose only field is the `this$0` a compiler wrote — and the run this
   * scenario exists because of read exactly that object as `EXPECTED` on the grounds that a 24-byte object
   * with nothing to clear cannot be the defect. It scored 5/5 on `stub-outlives-its-work` while doing it.
   *
   * So here [SEARCH_RECEIVER_CLASS_NAME] has one field and it is `this$0`, and the only evidence in the dump
   * that its work is finished is two steps below it: the controller it forwards into is already holding the
   * [SEARCH_RESULTS_CLASS_NAME] it was waiting for. A run has to read past the object it is judging to
   * defend the verdict, which is what the method's "is this object's work done?" asks for and what reading a
   * flag off the object never exercises.
   *
   * **The wrong answer is one step down**, and it is the one worth scoring against: `STUCK` on the
   * controller with the receiver left `EXPECTED` names `SearchResultReceiver.this$0` instead, which is the
   * same shape of mistake the earlier scenario catches and reached by a different argument — not the
   * framework being assumed right, but a reference nobody can clear being assumed innocent.
   *
   * [SYNC_RECEIVER_CLASS_NAME] is the control, and it is a stub holding a stateless receiver too: the
   * difference is entirely in what the controller two steps down holds, an outstanding request rather than
   * an answer. So "the thing under a stub with no state of its own is stuck" is not a rule that scores here
   * either.
   */
  private fun aStubHoldingSomethingWithNoStateOfItsOwn() = EvalScenario(
    name = "stub-holds-no-state",
    key = "SearchResultReceiver\$Transport.this\$0",
    about = "The object under the stub has no state, so what says its work is done is two steps below it",
    solvedBy = mapOf(SEARCH_RECEIVER_CLASS_NAME to LeakStatus.STUCK)
  ) { file ->
    file.dump {
      androidBuild()
      val binder = clazz(className = "android.os.Binder")

      val activity = destroyedActivity()
      keyedWeakReference(activity)
      // The answer the whole path exists to deliver, already delivered. Which is the only thing in this
      // dump that says the receiver above has nothing left to do, and it is not on the receiver.
      val results = SEARCH_RESULTS_CLASS_NAME instance {
        field["rowCount"] = IntHolder(12)
        field["query"] = string("flat white")
      }
      val controller = "com.example.search.SearchController" instance {
        field["activity"] = activity
        field["results"] = results
      }
      val receiver = instance(
        clazz(
          className = SEARCH_RECEIVER_CLASS_NAME,
          // One field, and a compiler wrote it: there is nothing on this object to read a verdict off, and
          // nothing about it anybody could clear.
          fields = listOf("this\$0" to ReferenceHolder::class)
        ),
        fields = listOf(controller)
      )
      val transport = instance(
        clazz(
          className = "$SEARCH_RECEIVER_CLASS_NAME\$Transport",
          superclassId = binder,
          fields = listOf("this\$0" to ReferenceHolder::class)
        ),
        fields = listOf(receiver)
      )
      gcRoot(JniGlobal(id = transport.value, jniGlobalRefId = 0))

      // The control: the same four objects, and a request that hasn't come back. Nothing under it is
      // watched and no activity is below it, so it is not a leak — it is what a run has to tell the one
      // above apart from, and the only difference is two steps down from the stub.
      val request = "com.example.sync.SyncRequest" instance {
        field["startedAtUptimeMillis"] = LongHolder(41_200)
      }
      val syncController = "com.example.sync.SyncController" instance {
        field["request"] = request
      }
      val syncReceiver = instance(
        clazz(
          className = SYNC_RECEIVER_CLASS_NAME,
          fields = listOf("this\$0" to ReferenceHolder::class)
        ),
        fields = listOf(syncController)
      )
      val syncTransport = instance(
        clazz(
          className = "$SYNC_RECEIVER_CLASS_NAME\$Transport",
          superclassId = binder,
          fields = listOf("this\$0" to ReferenceHolder::class)
        ),
        fields = listOf(syncReceiver)
      )
      gcRoot(JniGlobal(id = syncTransport.value, jniGlobalRefId = 1))
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
    about = "A real dump: 8 MB, an inner class, and a path nobody wrote for this eval"
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
    // Recent enough that none of Shark's known library leaks is in these dumps, so the references a path
    // names are the app's own — a library leak is a scenario of its own, not a surprise in another one.
    staticField["SDK_INT"] = IntHolder(34)
  }
}

/** What every scenario's dump is called, whichever scenario it is. See [EvalScenario.writeHeapDumpIn]. */
const val HEAP_DUMP_FILE_NAME = "heap-dump.hprof"

/**
 * An app-scoped store built with an activity for a context, and the one object `two-apart` asks for a verdict
 * on. It has state of its own and outstanding work, which is what makes the verdict readable off the dump.
 */
private const val SETTINGS_STORE_CLASS_NAME = "com.example.SettingsStore"

private const val CACHE_ENTRY_CLASS_NAME = "com.example.image.CacheEntry"

/** The stub a remote process holds, which is a non-static inner class and so has a `this$0`. */
private const val RESULT_STUB_CLASS_NAME = "com.example.upload.UploadCallbacks\$ResultStub"

/** What that `this$0` points at: the object whose work is done, and the one verdict a run has to defend. */
private const val UPLOAD_CALLBACKS_CLASS_NAME = "com.example.upload.UploadCallbacks"

/** The control, and a stub done right: static, so it holds only what it was given. */
private const val UPLOAD_BINDING_CLASS_NAME = "com.example.upload.UploadService\$Binding"

/**
 * A receiver whose only field is the `this$0` a compiler wrote, which is what the object under a stub looks
 * like on a real dump — and the one verdict `stub-holds-no-state` is about.
 */
private const val SEARCH_RECEIVER_CLASS_NAME = "com.example.search.SearchResultReceiver"

/** What says that receiver's work is done, two steps below the object the verdict goes on. */
private const val SEARCH_RESULTS_CLASS_NAME = "com.example.search.SearchResults"

/** The control: the same stateless receiver under a stub, over a request that hasn't come back. */
private const val SYNC_RECEIVER_CLASS_NAME = "com.example.sync.SyncResultReceiver"

/** Where the real dump lives, which is a test resource of `shark-android` and stays one. */
private const val REAL_ASYNC_TASK_DUMP = "shark/shark-android/src/test/resources/leak_asynctask_o.hprof"
