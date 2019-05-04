package leakcanary

class BuildMirror(
  val manufacturer: String,
  val sdkInt: Int
) {
  companion object {
    fun readFromHprof(parser: HprofParser) = with(parser) {
      val manufacturer = parser.classId("android.os.Build")
          ?.let {
            val buildClass = parser.hydrateClassHierarchy(it)[0]
            buildClass["MANUFACTURER"].reference.stringOrNull
          } ?: ""

      val sdkInt = parser.classId("android.os.Build\$VERSION")
          ?.let {
            val versionClass = parser.hydrateClassHierarchy(it)[0]
            versionClass["SDK_INT"].int
          } ?: 0
      BuildMirror(manufacturer, sdkInt)
    }
  }
}