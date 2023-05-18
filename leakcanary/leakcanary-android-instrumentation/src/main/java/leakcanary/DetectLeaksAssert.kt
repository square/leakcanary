package leakcanary

/**
 * The interface for the implementation that [LeakAssertions.assertNoLeaks] delegates to.
 * You can call [DetectLeaksAssert.update] to provide your own implementation.
 *
 * The default implementation is [AndroidDetectLeaksAssert].
 */
fun interface DetectLeaksAssert {

  fun assertNoLeaks(tag: String)

  companion object {
    @Volatile
    internal var delegate: DetectLeaksAssert = AndroidDetectLeaksAssert()

    fun update(delegate: DetectLeaksAssert) {
      DetectLeaksAssert.delegate = delegate
    }
  }
}
