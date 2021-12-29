package leakcanary

/**
 * Deprecated because this relies on hacks built on top of AndroidX Test internals which keep
 * changing. Use [LeakAssertions] instead.
 *
 * An [Annotation] class to be used in conjunction with [FailAnnotatedTestOnLeakRunListener]
 * for detecting memory leaks. When using [FailAnnotatedTestOnLeakRunListener], the tests
 * should be annotated with this class in order for the listener to detect memory leaks.
 *
 * @see FailAnnotatedTestOnLeakRunListener
 */
@Deprecated("Use LeakAssertions instead")
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class FailTestOnLeak
