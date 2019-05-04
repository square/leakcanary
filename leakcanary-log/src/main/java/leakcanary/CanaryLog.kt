package leakcanary

object CanaryLog {

  interface Logger {
    fun d(
      message: String,
      vararg args: Any?
    )

    fun d(
      throwable: Throwable?,
      message: String,
      vararg args: Any?
    )
  }

  @Volatile var logger: Logger? = null

  fun d(
    message: String,
    vararg args: Any?
  ) {
    // Local variable to prevent the ref from becoming null after the null check.
    val logger = logger ?: return
    logger.d(message, *args)
  }

  fun d(
    throwable: Throwable?,
    message: String,
    vararg args: Any?
  ) {
    // Local variable to prevent the ref from becoming null after the null check.
    val logger = logger ?: return
    logger.d(throwable, message, *args)
  }
}
