package leakcanary

import java.util.ServiceLoader

/**
 * The interface for the implementation that [LeakAssertions.assertNoLeaks] delegates to.
 * You can call [DetectLeaksAssert.update] to provide your own implementation.
 *
 * The default implementation is [AndroidDetectLeaksAssert] or to a discoverable [DetectLeaksAssert]
 * through the classpath at `META-INF/services/leakcanary.DetectLeaksAssert`.
 */
fun interface DetectLeaksAssert {

  fun assertNoLeaks(tag: String)

  companion object {
    @Volatile
    internal var delegate: DetectLeaksAssert =
      ServiceLoader.load(DetectLeaksAssert::class.java).singleOrNull()
        ?: AndroidDetectLeaksAssert()

    fun update(delegate: DetectLeaksAssert) {
      DetectLeaksAssert.delegate = delegate
    }
  }
}
