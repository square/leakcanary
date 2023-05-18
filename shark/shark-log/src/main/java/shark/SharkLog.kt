package shark

/**
 * Central Logger for all Shark artifacts. Set [logger] to change where these logs go.
 */
object SharkLog {

  /**
   * @see SharkLog
   */
  interface Logger {

    /**
     * Logs a debug message formatted with the passed in arguments.
     */
    fun d(message: String)

    /**
     * Logs a [Throwable] and debug message formatted with the passed in arguments.
     */
    fun d(
      throwable: Throwable,
      message: String
    )
  }

  @Volatile var logger: Logger? = null

  /**
   * @see Logger.d
   */
  inline fun d(message: () -> String) {
    // Local variable to prevent the ref from becoming null after the null check.
    val logger = logger ?: return
    logger.d(message.invoke())
  }

  /**
   * @see Logger.d
   */
  inline fun d(
    throwable: Throwable,
    message: () -> String
  ) {
    // Local variable to prevent the ref from becoming null after the null check.
    val logger = logger ?: return
    logger.d(throwable, message.invoke())
  }
}
