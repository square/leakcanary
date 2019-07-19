package leakcanary

/**
 * Central Logger for all LeakCanary artifacts. Set [logger] to change where these logs go.
 */
object CanaryLog {

  /**
   * @see CanaryLog
   */
  interface Logger {

    /**
     * Logs a debug message formatted with the passed in arguments.
     */
    fun d(
      message: String,
      vararg args: Any?
    )

    /**
     * Logs a [Throwable] and debug message formatted with the passed in arguments.
     */
    fun d(
      throwable: Throwable,
      message: String,
      vararg args: Any?
    )
  }

  @Volatile var logger: Logger? = null

  /**
   * @see Logger.d
   */
  fun d(
    message: String,
    vararg args: Any?
  ) {
    // Local variable to prevent the ref from becoming null after the null check.
    val logger = logger ?: return
    logger.d(message, *args)
  }

  /**
   * @see Logger.d
   */
  fun d(
    throwable: Throwable,
    message: String,
    vararg args: Any?
  ) {
    // Local variable to prevent the ref from becoming null after the null check.
    val logger = logger ?: return
    logger.d(throwable, message, *args)
  }
}
