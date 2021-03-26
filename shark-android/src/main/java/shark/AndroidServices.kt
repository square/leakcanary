package shark

object AndroidServices {
  val HeapGraph.aliveAndroidServiceObjectIds: List<Long>
    get() {
      return context.getOrPut(AndroidServices::class.java.name) {
        val activityThreadClass = findClassByName("android.app.ActivityThread")!!
        val currentActivityThread = activityThreadClass
          .readStaticField("sCurrentActivityThread")!!
          .valueAsInstance!!

        val mServices = currentActivityThread["android.app.ActivityThread", "mServices"]!!
          .valueAsInstance!!

        val servicesArray = mServices["android.util.ArrayMap", "mArray"]!!.valueAsObjectArray!!

        servicesArray.readElements()
          .filterIndexed { index, heapValue ->
            // ArrayMap<IBinder, Service>
            // even: key, odd: value
            index % 2 == 1
              && heapValue.isNonNullReference
          }
          .map { it.asNonNullObjectId!! }
          .toList()
      }
    }
}