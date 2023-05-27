package shark

// TODO Name?
data class LeaksAndUnreachableObjects(
  val applicationLeaks: List<ApplicationLeak>,
  val libraryLeaks: List<LibraryLeak>,
  val unreachableObjects: List<LeakTraceObject>
)
