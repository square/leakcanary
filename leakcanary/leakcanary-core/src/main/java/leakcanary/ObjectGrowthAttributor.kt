package leakcanary

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import shark.HeapDiff
import shark.ReferenceLocationType.INSTANCE_FIELD
import shark.ReferenceLocationType.STATIC_FIELD
import shark.ShortestPathObjectNode

/**
 * Attributes the growth that `shark.ObjectGrowthDetector` found to the code that caused it, which is
 * the step described in section 4.2 of the BLeak paper
 * ([bleak](https://plasma-umass.org/bleak-paper.pdf)): once you know which object keeps growing,
 * run the scenario once more while watching that object, and keep a stack trace for every write that
 * grows it.
 *
 * BLeak watches growth by rewriting the growing container's mutating methods. That isn't available
 * here: ART can't retransform loaded classes, so `java.util.ArrayList.add()` can't be instrumented.
 * Instead, this swaps the growing collection out for a [Proxy] that records a stack trace on every
 * call that can add to it and then delegates to the collection it replaced, and swaps the original
 * back in when the scenario is done.
 *
 * That only works when the growing collection is held by a field this can find and write to, so
 * [attributeGrowth] returns [GrowthAttribution.NotAttributed] with a reason for each growing object
 * it can't watch rather than failing: the result is always at least as informative as the [HeapDiff]
 * it started from.
 *
 * Because the field ends up holding a proxy for the duration of the scenario, code that reads it and
 * casts to a concrete type (`field as ArrayList`) will fail while the scenario runs. Code that only
 * uses the interface the field is declared as is unaffected.
 */
class ObjectGrowthAttributor(
  /**
   * Objects that might own the growing instance fields.
   *
   * A growing object held by a static field is found from the class name alone, but an instance
   * field needs the instance that declares it, and there is no way to get from an object in a heap
   * dump back to the live object it was dumped from: Android heap dumps don't identify live objects,
   * and enumerating the instances of a class in process needs JVMTI. So callers name the candidates
   * instead, which in a test usually means the test instance itself plus whichever singletons the
   * scenario touches.
   */
  private val fieldOwners: List<Any> = emptyList(),
) {

  /**
   * Runs [roundTripScenario] [scenarioLoops] times with every growing object of [heapDiff] watched,
   * and returns one [GrowthAttribution] per [HeapDiff.growingObjects] entry, in the same order.
   *
   * [heapDiff] has to come from a detector that ran in this process, since this looks the growing
   * objects up as live objects.
   */
  fun attributeGrowth(
    heapDiff: HeapDiff,
    scenarioLoops: Int = 1,
    roundTripScenario: () -> Unit,
  ): List<GrowthAttribution> {
    require(scenarioLoops >= 1) {
      "scenarioLoops should be at least 1, was $scenarioLoops"
    }
    val watched = heapDiff.growingObjects.map { growingObject ->
      growingObject to watch(growingObject)
    }
    try {
      repeat(scenarioLoops) {
        roundTripScenario()
      }
    } finally {
      watched.forEach { (_, result) -> result.getOrNull()?.stopWatching() }
    }
    return watched.map { (growingObject, result) ->
      result.fold(
        onSuccess = { GrowthAttribution.Attributed(growingObject, it.recorder.growthStacks()) },
        onFailure = { GrowthAttribution.NotAttributed(growingObject, it.message!!) }
      )
    }
  }

  @Suppress("ReturnCount")
  private fun watch(growingObject: ShortestPathObjectNode): Result<WatchedField> {
    val location = growingObject.reference
      ?: return cannotWatch("it isn't reached through a reference")

    if (location.locationType != INSTANCE_FIELD && location.locationType != STATIC_FIELD) {
      return cannotWatch(
        "it's reached through a ${location.locationType} reference, which isn't a field that can " +
          "be swapped out"
      )
    }
    val isStatic = location.locationType == STATIC_FIELD
    val fieldId = "${location.owningClassName}.${location.referenceName}"

    val owningClass = try {
      Class.forName(location.owningClassName)
    } catch (notFound: ClassNotFoundException) {
      return cannotWatch("class ${location.owningClassName} isn't loaded in this process")
    }
    val field = try {
      owningClass.getDeclaredField(location.referenceName)
    } catch (notFound: NoSuchFieldException) {
      return cannotWatch("$fieldId isn't a declared field of ${owningClass.name}")
    }
    if (!field.type.isInterface) {
      return cannotWatch(
        "$fieldId is declared as ${field.type.name}, which isn't an interface, so it can't be made " +
          "to hold a recording proxy"
      )
    }
    if (isStatic && Modifier.isFinal(field.modifiers)) {
      return cannotWatch("$fieldId is static final, which reflection can't write to")
    }
    field.isAccessible = true

    val owner = if (isStatic) {
      null
    } else {
      val owners = fieldOwners.filter { owningClass.isInstance(it) }
      when (owners.size) {
        0 -> return cannotWatch(
          "$fieldId is an instance field and none of the ${fieldOwners.size} objects passed as " +
            "fieldOwners is a ${owningClass.name}; pass the instance that declares it"
        )
        1 -> owners.single()
        else -> return cannotWatch(
          "$fieldId is an instance field and ${owners.size} of the objects passed as fieldOwners " +
            "are a ${owningClass.name}, so there's no telling which one grew"
        )
      }
    }

    val original = field.get(owner)
      ?: return cannotWatch("$fieldId is null")

    val recorder = MutationRecorder(original)
    val proxy = recorder.newProxy()
      ?: return cannotWatch(
        "$fieldId holds a ${original.javaClass.name}, which is neither a Collection nor a Map"
      )
    return try {
      field.set(owner, proxy)
      Result.success(WatchedField(field, owner, original, recorder))
    } catch (cannotWrite: IllegalAccessException) {
      cannotWatch("$fieldId can't be written to by reflection: ${cannotWrite.message}")
    }
  }

  private fun cannotWatch(reason: String): Result<WatchedField> =
    Result.failure(UnsupportedOperationException(reason))

  private class WatchedField(
    private val field: Field,
    private val owner: Any?,
    private val original: Any,
    val recorder: MutationRecorder,
  ) {
    fun stopWatching() {
      field.set(owner, original)
    }
  }

  /**
   * Delegates every call to [original] and counts the stack traces of the calls that can grow it.
   */
  private class MutationRecorder(
    private val original: Any,
  ) {
    private val countByCall = mutableMapOf<RecordedCall, Int>()

    fun newProxy(): Any? {
      val interfaces = original.javaClass.publicInterfaces()
      val growable = interfaces.any { it == Collection::class.java || it == Map::class.java }
      if (!growable) {
        return null
      }
      return Proxy.newProxyInstance(
        original.javaClass.classLoader,
        interfaces.toTypedArray()
      ) { _, method, args ->
        if (method.name in GROWING_METHOD_NAMES) {
          record(method.name)
        }
        try {
          method.invoke(original, *(args ?: EMPTY_ARGS))
        } catch (thrownByOriginal: InvocationTargetException) {
          throw thrownByOriginal.cause ?: thrownByOriginal
        }
      }
    }

    fun growthStacks(): List<GrowthStack> {
      val snapshot = synchronized(this) { countByCall.toMap() }
      return snapshot.entries
        .sortedByDescending { it.value }
        .map { (call, count) -> GrowthStack(call.methodName, call.stackTrace, count) }
    }

    private fun record(methodName: String) {
      val call = RecordedCall(methodName, callerStackTrace())
      // The scenario may grow the object from several threads at once.
      synchronized(this) {
        countByCall[call] = (countByCall[call] ?: 0) + 1
      }
    }

    /**
     * The stack trace of whoever called the proxy, with the frames of the proxy and of this
     * recorder dropped so that the first frame is the code that grew the object.
     */
    private fun callerStackTrace(): List<StackTraceElement> {
      val frames = Throwable().stackTrace
      // Proxy classes are named "$Proxy0", prefixed with a package that varies by runtime.
      val proxyFrame = frames.indexOfFirst { it.className.contains("\$Proxy") }
      return if (proxyFrame == -1) frames.toList() else frames.drop(proxyFrame + 1)
    }
  }

  private data class RecordedCall(
    val methodName: String,
    val stackTrace: List<StackTraceElement>,
  )

  private companion object {
    val EMPTY_ARGS = emptyArray<Any?>()

    /**
     * Methods of the JDK collection interfaces that can add to a collection or a map. Methods that
     * replace an existing entry without adding one, like `List.set()` and `Map.replace()`, are left
     * out: they don't grow anything.
     */
    val GROWING_METHOD_NAMES = setOf(
      "add", "addAll", "addFirst", "addLast",
      "offer", "offerFirst", "offerLast", "push",
      "put", "putAll", "putIfAbsent",
      "merge", "compute", "computeIfAbsent", "computeIfPresent",
    )

    /**
     * Every public interface implemented by this class or one of its supertypes. A proxy has to be
     * assignable to the field it replaces the value of, and to whatever else the scenario casts it
     * to, so it implements all of them rather than just the declared field type. Non public
     * interfaces are left out because a proxy can only implement those when they all live in the
     * same package.
     */
    fun Class<*>.publicInterfaces(): Set<Class<*>> {
      val interfaces = LinkedHashSet<Class<*>>()

      fun addWithSuperInterfaces(interfaceClass: Class<*>) {
        if (Modifier.isPublic(interfaceClass.modifiers) && interfaces.add(interfaceClass)) {
          interfaceClass.interfaces.forEach { addWithSuperInterfaces(it) }
        }
      }

      var current: Class<*>? = this
      while (current != null) {
        current.interfaces.forEach { addWithSuperInterfaces(it) }
        current = current.superclass
      }
      return interfaces
    }
  }
}
