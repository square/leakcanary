package shark

import java.util.EnumSet

enum class HprofRecordTag(val tag: Int) {
  STRING_IN_UTF8(0x01),
  LOAD_CLASS(0x02),
  // Currently ignored
  UNLOAD_CLASS(0x03),
  STACK_FRAME(0x04),
  STACK_TRACE(0x05),
  // Currently ignored
  ALLOC_SITES(0x06),
  // Currently ignored
  HEAP_SUMMARY(0x07),
  // Currently ignored
  START_THREAD(0x0a),
  // Currently ignored
  END_THREAD(0x0b),
  // Currently not reported
  HEAP_DUMP(0x0c),
  // Currently not reported
  HEAP_DUMP_SEGMENT(0x1c),
  HEAP_DUMP_END(0x2c),
  // Currently ignored
  CPU_SAMPLES(0x0d),
  // Currently ignored
  CONTROL_SETTINGS(0x0e),
  ROOT_UNKNOWN(0xff),
  ROOT_JNI_GLOBAL(0x01),
  ROOT_JNI_LOCAL(0x02),
  ROOT_JAVA_FRAME(0x03),
  ROOT_NATIVE_STACK(0x04),
  ROOT_STICKY_CLASS(0x05),

  // An object that was referenced from an active thread block.
  ROOT_THREAD_BLOCK(0x06),
  ROOT_MONITOR_USED(0x07),
  ROOT_THREAD_OBJECT(0x08),

  /**
   * Android format addition
   *
   * Specifies information about which heap certain objects came from. When a sub-tag of this type
   * appears in a HPROF_HEAP_DUMP or HPROF_HEAP_DUMP_SEGMENT record, entries that follow it will
   * be associated with the specified heap.  The HEAP_DUMP_INFO data is reset at the end of the
   * HEAP_DUMP[_SEGMENT].  Multiple HEAP_DUMP_INFO entries may appear in a single
   * HEAP_DUMP[_SEGMENT].
   *
   * Format: u1: Tag value (0xFE) u4: heap ID ID: heap name string ID
   */
  HEAP_DUMP_INFO(0xfe),
  ROOT_INTERNED_STRING(0x89),
  ROOT_FINALIZING(0x8a),
  ROOT_DEBUGGER(0x8b),
  ROOT_REFERENCE_CLEANUP(0x8c),
  ROOT_VM_INTERNAL(0x8d),
  ROOT_JNI_MONITOR(0x8e),
  ROOT_UNREACHABLE(0x90),
  // Not supported.
  PRIMITIVE_ARRAY_NODATA(0xc3),
  CLASS_DUMP(0x20),
  INSTANCE_DUMP(0x21),
  OBJECT_ARRAY_DUMP(0x22),
  PRIMITIVE_ARRAY_DUMP(0x23),
  ;

  companion object {
    val rootTags: EnumSet<HprofRecordTag> = EnumSet.of(
        ROOT_UNKNOWN,
        ROOT_JNI_GLOBAL,
        ROOT_JNI_LOCAL,
        ROOT_JAVA_FRAME,
        ROOT_NATIVE_STACK,
        ROOT_STICKY_CLASS,
        ROOT_THREAD_BLOCK,
        ROOT_MONITOR_USED,
        ROOT_THREAD_OBJECT,
        ROOT_INTERNED_STRING,
        ROOT_FINALIZING,
        ROOT_DEBUGGER,
        ROOT_REFERENCE_CLEANUP,
        ROOT_VM_INTERNAL,
        ROOT_JNI_MONITOR,
        ROOT_UNREACHABLE
    )
  }

}