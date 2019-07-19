package leakcanary

/**
 * An [Annotation] class to be used in conjunction with [FailAnnotatedTestOnLeakRunListener]
 * for detecting memory leaks. When using [FailAnnotatedTestOnLeakRunListener], the tests
 * should be annotated with this class in order for the listener to detect memory leaks.
 *
 * @see FailAnnotatedTestOnLeakRunListener
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class FailTestOnLeak