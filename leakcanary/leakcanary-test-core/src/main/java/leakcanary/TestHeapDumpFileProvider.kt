package leakcanary

class TestHeapDumpFileProvider(
  heapDumpDirectoryProvider: HeapDumpDirectoryProvider
) : HeapDumpFileProvider {

  private val delegate = DatetimeFormattedHeapDumpFileProvider(
    heapDumpDirectoryProvider = heapDumpDirectoryProvider,
    suffixProvider = {
      TestNameProvider.currentTestName()?.run {
        // JVM test method names can have spaces.
        val escapedMethodName = methodName.replace(' ', '-')
        "_${classSimpleName}-${escapedMethodName}"
      } ?: ""
    }
  )

  override fun newHeapDumpFile() = delegate.newHeapDumpFile()
}
