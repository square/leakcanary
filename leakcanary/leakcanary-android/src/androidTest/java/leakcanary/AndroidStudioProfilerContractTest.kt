package leakcanary

import java.lang.reflect.Proxy
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Android Studio's Profiler runs LeakCanary's analysis on the host instead of the device. It does
 * that through a bridge library it injects into the app,
 * `com.android.tools.studio.leakcanary:leakcanary`, whose source is in AOSP at
 * `tools/base/studio-leakcanary`. The bridge deliberately has no compile time dependency on
 * LeakCanary, so every class, method and field it touches is resolved reflectively by name. Nothing
 * on our side declares those names, and nothing in the ABI dump distinguishes them from any other
 * member, so renaming one of them breaks the Profiler with no signal here at all: the bridge
 * catches the failure, reports the retained object count as unavailable, and Studio tells the user
 * LeakCanary is missing from their app.
 *
 * This test replays what the bridge does against the artifact an app actually gets, so that a
 * rename shows up as a failure naming the member. It is a transcription of
 * `LeakCanaryReflectionHelper` and `StudioLeakCanaryListener` in the directory above, and the
 * strings below are copied from that library's `HelperConfig`; when they change there, they need to
 * change here. Studio bundles its own copy of Shark to read the heap dump, currently
 * `shark-android-2.14.jar`, so [keyedWeakReferenceFieldsReadFromHeapDump] covers the field names
 * that copy looks up in the dump rather than on the classpath.
 */
class AndroidStudioProfilerContractTest {

  /**
   * The bridge resolves all of this in one `try` block and gives up on the first failure, so a
   * single missing member costs the whole integration, not one feature of it. Resolving it in the
   * same order here means the failure points at the first member that moved.
   */
  @Test fun bridgeResolvesEverythingItNeeds() {
    val leakCanaryClass = Class.forName(LEAK_CANARY_CLASS)
    val leakCanaryInstance = leakCanaryClass.getDeclaredField(INSTANCE_FIELD).get(null)
    assertThat(leakCanaryInstance).isNotNull()

    // Resolved but not invoked: it dumps the heap for real. The bridge calls it from its
    // ForceDumpReceiver, when Studio asks for an on device dump.
    assertThat(leakCanaryClass.getMethod(DUMP_HEAP_METHOD)).isNotNull()

    val getConfigMethod = leakCanaryClass.getDeclaredMethod(GET_CONFIG_METHOD)
    val config = getConfigMethod.invoke(leakCanaryInstance)
    assertThat(config).isNotNull()

    val internalLeakCanaryClass = Class.forName(LEAK_CANARY_INTERNAL_CLASS)
    val internalInstance = internalLeakCanaryClass.getDeclaredField(INSTANCE_FIELD)
      .apply { isAccessible = true }
      .get(null)
    val listenerInterface = Class.forName(ON_OBJECT_RETAINED_LISTENER_CLASS)
    // The bridge passes this instance to removeOnObjectRetainedListener() to unhook LeakCanary's
    // own listener, which only works if it still implements the listener interface.
    assertThat(internalInstance).isInstanceOf(listenerInterface)

    val appWatcherClass = Class.forName(APP_WATCHER_CLASS)
    val appWatcherInstance = appWatcherClass.getDeclaredField(INSTANCE_FIELD).get(null)
    val objectWatcher = appWatcherClass.getMethod(GET_OBJECT_WATCHER_METHOD)
      .invoke(appWatcherInstance)!!

    val objectWatcherClass = objectWatcher.javaClass
    assertThat(objectWatcherClass.getMethod(GET_RETAINED_OBJECT_COUNT_METHOD)).isNotNull()
    assertThat(
      objectWatcherClass.getMethod(
        CLEAR_OBJECTS_WATCHED_BEFORE_METHOD,
        Long::class.javaPrimitiveType
      )
    ).isNotNull()
    assertThat(
      objectWatcherClass.getMethod(
        ADD_ON_OBJECT_RETAINED_LISTENER_METHOD,
        listenerInterface
      )
    ).isNotNull()
    assertThat(
      objectWatcherClass.getMethod(
        REMOVE_ON_OBJECT_RETAINED_LISTENER_METHOD,
        listenerInterface
      )
    ).isNotNull()

    val gcTriggerClass = Class.forName(GC_TRIGGER_DEFAULT_CLASS)
    val gcTriggerInstance = gcTriggerClass.getDeclaredField(INSTANCE_FIELD).get(null)!!
    assertThat(gcTriggerClass.getMethod(RUN_GC_METHOD)).isNotNull()
    // The bridge looks runGc() up on the instance's class, not on the class it named, so an
    // INSTANCE typed as something else would still have to expose it.
    assertThat(gcTriggerInstance.javaClass.getMethod(RUN_GC_METHOD)).isNotNull()
  }

  /**
   * Studio dumps the heap itself, so it turns LeakCanary's own dumping off by writing the `val`
   * behind [LeakCanary.Config.dumpHeap] in place, on the config instance that is already installed.
   * Whether that write is permitted is up to the runtime, which is why it is asserted here rather
   * than reasoned about.
   */
  @Test fun bridgeTurnsOffOnDeviceHeapDumping() {
    val leakCanaryClass = Class.forName(LEAK_CANARY_CLASS)
    val leakCanaryInstance = leakCanaryClass.getDeclaredField(INSTANCE_FIELD).get(null)
    val config = leakCanaryClass.getDeclaredMethod(GET_CONFIG_METHOD).invoke(leakCanaryInstance)!!
    val dumpHeapField = config.javaClass.getDeclaredField(DUMP_HEAP_FIELD)
      .apply { isAccessible = true }
    val original = dumpHeapField.getBoolean(config)
    try {
      dumpHeapField.setBoolean(config, false)
      assertThat(LeakCanary.config.dumpHeap).isFalse()
      dumpHeapField.setBoolean(config, true)
      assertThat(LeakCanary.config.dumpHeap).isTrue()
    } finally {
      dumpHeapField.setBoolean(config, original)
    }
  }

  /**
   * Studio decides for itself when the retained count is worth a heap dump, so it reads the
   * threshold out of the config to match LeakCanary's own trigger point. A failure to read it is
   * how the bridge concludes LeakCanary is absent: it answers Studio with -1, and Studio then
   * tells the user to add the dependency.
   */
  @Test fun bridgeReadsRetainedVisibleThreshold() {
    val leakCanaryClass = Class.forName(LEAK_CANARY_CLASS)
    val leakCanaryInstance = leakCanaryClass.getDeclaredField(INSTANCE_FIELD).get(null)
    val config = leakCanaryClass.getDeclaredMethod(GET_CONFIG_METHOD).invoke(leakCanaryInstance)!!
    val threshold = config.javaClass
      .getMethod(GET_RETAINED_VISIBLE_THRESHOLD_METHOD)
      .invoke(config)

    assertThat(threshold).isEqualTo(LeakCanary.config.retainedVisibleThreshold)
  }

  /**
   * The bridge replaces LeakCanary's listener with a [Proxy] of its own so that a retained object
   * reaches Studio instead of triggering a dump on the device, and puts LeakCanary's back when
   * Studio stops profiling. Both directions have to work, since the second one is what leaves the
   * app usable after profiling.
   */
  @Test fun bridgeSwapsTheRetainedObjectListenerAndPutsItBack() {
    val listenerInterface = Class.forName(ON_OBJECT_RETAINED_LISTENER_CLASS)
    val internalInstance = Class.forName(LEAK_CANARY_INTERNAL_CLASS)
      .getDeclaredField(INSTANCE_FIELD)
      .apply { isAccessible = true }
      .get(null)

    var proxyNotified = false
    val studioListener = Proxy.newProxyInstance(
      listenerInterface.classLoader,
      arrayOf(listenerInterface)
    ) { proxy, method, args ->
      when (method.name) {
        ON_OBJECT_RETAINED_METHOD -> {
          proxyNotified = true
          null
        }
        // The bridge answers these itself so the proxy survives the set it is stored in. Identity
        // equality is what lets it be taken back out again by the same reference.
        "toString" -> "StudioLeakCanaryListenerProxy"
        "hashCode" -> 0
        "equals" -> args!![0] === proxy
        else -> null
      }
    }

    val objectWatcher = AppWatcher.objectWatcher
    val objectWatcherClass = objectWatcher.javaClass
    val addListener =
      objectWatcherClass.getMethod(ADD_ON_OBJECT_RETAINED_LISTENER_METHOD, listenerInterface)
    val removeListener =
      objectWatcherClass.getMethod(REMOVE_ON_OBJECT_RETAINED_LISTENER_METHOD, listenerInterface)

    try {
      // Accepting the proxy at all is part of the contract: the watcher's parameter type is the
      // interface the proxy was created from.
      removeListener.invoke(objectWatcher, internalInstance)
      addListener.invoke(objectWatcher, studioListener)

      // The bridge's handler only recognises a method literally named onObjectRetained, and
      // returns a default for anything else, so a rename would silently stop Studio from ever
      // learning there is something to dump.
      listenerInterface.getMethod(ON_OBJECT_RETAINED_METHOD).invoke(studioListener)
      assertThat(proxyNotified).isTrue()
    } finally {
      removeListener.invoke(objectWatcher, studioListener)
      addListener.invoke(objectWatcher, internalInstance)
    }
  }

  /**
   * Once Studio has analysed a dump on the host it tells the bridge to forget the objects that
   * dump already accounted for, otherwise the same retained objects keep asking for another one.
   */
  @Test fun bridgeClearsObjectsWatchedBeforeAHeapDump() {
    val objectWatcher = AppWatcher.objectWatcher
    val clearObjectsWatchedBefore = objectWatcher.javaClass.getMethod(
      CLEAR_OBJECTS_WATCHED_BEFORE_METHOD,
      Long::class.javaPrimitiveType
    )
    val getRetainedObjectCount =
      objectWatcher.javaClass.getMethod(GET_RETAINED_OBJECT_COUNT_METHOD)

    clearObjectsWatchedBefore.invoke(objectWatcher, Long.MAX_VALUE)

    assertThat(getRetainedObjectCount.invoke(objectWatcher)).isEqualTo(0)
  }

  /**
   * The bridge runs a GC before counting retained objects, so that what it reports to Studio is
   * what would still be there after collection. It reaches for the GC trigger by the name the
   * nested `object GcTrigger.Default` compiled to up to LeakCanary 2.14; `GcTrigger$Default` is
   * kept for that lookup alone.
   */
  @Test fun bridgeRunsGcBeforeCounting() {
    val gcTriggerInstance = Class.forName(GC_TRIGGER_DEFAULT_CLASS)
      .getDeclaredField(INSTANCE_FIELD)
      .get(null)!!

    gcTriggerInstance.javaClass.getMethod(RUN_GC_METHOD).invoke(gcTriggerInstance)

    assertThat(gcTriggerInstance).isInstanceOf(GcTrigger::class.java)
  }

  /**
   * Studio's bundled Shark finds the leaking objects in the heap dump by reading these fields off
   * `leakcanary.KeyedWeakReference` instances in the dump, by name. They are read out of the file,
   * not off the classpath, so no version of LeakCanary can adapt: renaming one makes Studio report
   * no leaks at all for a dump that has them.
   */
  @Test fun keyedWeakReferenceFieldsReadFromHeapDump() {
    val keyedWeakReferenceClass = Class.forName("leakcanary.KeyedWeakReference")

    assertThat(keyedWeakReferenceClass.getDeclaredField("key").type).isEqualTo(String::class.java)
    assertThat(keyedWeakReferenceClass.getDeclaredField("description").type)
      .isEqualTo(String::class.java)
    assertThat(keyedWeakReferenceClass.getDeclaredField("watchUptimeMillis").type)
      .isEqualTo(Long::class.javaPrimitiveType)
    assertThat(keyedWeakReferenceClass.getDeclaredField("retainedUptimeMillis").type)
      .isEqualTo(Long::class.javaPrimitiveType)
    // Static, and read as such: it is how the retained duration of every object in the dump is
    // worked out.
    val heapDumpUptimeMillis = keyedWeakReferenceClass.getDeclaredField("heapDumpUptimeMillis")
    assertThat(heapDumpUptimeMillis.type).isEqualTo(Long::class.javaPrimitiveType)
    assertThat(java.lang.reflect.Modifier.isStatic(heapDumpUptimeMillis.modifiers)).isTrue()
  }

  companion object {
    private const val LEAK_CANARY_CLASS = "leakcanary.LeakCanary"
    private const val LEAK_CANARY_INTERNAL_CLASS = "leakcanary.internal.InternalLeakCanary"
    private const val APP_WATCHER_CLASS = "leakcanary.AppWatcher"
    private const val ON_OBJECT_RETAINED_LISTENER_CLASS = "leakcanary.OnObjectRetainedListener"
    private const val GC_TRIGGER_DEFAULT_CLASS = "leakcanary.GcTrigger\$Default"
    private const val INSTANCE_FIELD = "INSTANCE"
    private const val DUMP_HEAP_FIELD = "dumpHeap"
    private const val DUMP_HEAP_METHOD = "dumpHeap"
    private const val GET_CONFIG_METHOD = "getConfig"
    private const val GET_OBJECT_WATCHER_METHOD = "getObjectWatcher"
    private const val GET_RETAINED_OBJECT_COUNT_METHOD = "getRetainedObjectCount"
    private const val CLEAR_OBJECTS_WATCHED_BEFORE_METHOD = "clearObjectsWatchedBefore"
    private const val ADD_ON_OBJECT_RETAINED_LISTENER_METHOD = "addOnObjectRetainedListener"
    private const val REMOVE_ON_OBJECT_RETAINED_LISTENER_METHOD = "removeOnObjectRetainedListener"
    private const val ON_OBJECT_RETAINED_METHOD = "onObjectRetained"
    private const val RUN_GC_METHOD = "runGc"
    private const val GET_RETAINED_VISIBLE_THRESHOLD_METHOD = "getRetainedVisibleThreshold"
  }
}
