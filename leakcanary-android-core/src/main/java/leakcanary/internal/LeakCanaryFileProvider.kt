package leakcanary.internal

import androidx.core.content.FileProvider

/**
 * There can only be one [FileProvider] provider registered per app, so we extend that class
 * just to use a distinct name.
 */
internal class LeakCanaryFileProvider : FileProvider()
