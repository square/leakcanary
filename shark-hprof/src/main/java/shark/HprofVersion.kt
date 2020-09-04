package shark

/**
 * Supported hprof versions
 */
enum class HprofVersion(val versionString: String) {
  JDK1_2_BETA3("JAVA PROFILE 1.0"),
  JDK1_2_BETA4("JAVA PROFILE 1.0.1"),
  JDK_6("JAVA PROFILE 1.0.2"),
  ANDROID("JAVA PROFILE 1.0.3")
}
