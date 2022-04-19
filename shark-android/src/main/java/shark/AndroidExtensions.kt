package shark

import shark.HeapObject.HeapInstance

/**
 * The system identity hash code, or null if it couldn't be found.
 *
 * Based on the Object.identityHashCode implementation in AOSP.
 *
 * Backing field shadow$_monitor_ was added in API 24.
 * https://cs.android.com/android/_/android/platform/libcore/+
 * /de626ec8a109ea18283d96c720cc57e2f32f67fa:ojluni/src/main/java/java/lang/Object.java;
 * dlc=ba7cc9f5357c323a1006119a20ce025fd4c57fd2
 */
val HeapInstance.identityHashCode: Int?
  get() {
    // Top 2 bits.
    val lockWordStateMask = -0x40000000
    // Top 2 bits are value 2 (kStateHash).
    val lockWordStateHash = -0x80000000
    // Low 28 bits.
    val lockWordHashMask = 0x0FFFFFFF
    val lockWord = this["java.lang.Object", "shadow\$_monitor_"]?.value?.asInt
    return if (lockWord != null && lockWord and lockWordStateMask == lockWordStateHash) {
      lockWord and lockWordHashMask
    } else null
  }

/**
 * The system identity hashCode represented as hex, or null if it couldn't be found.
 * This is the string identifier you see when calling Object.toString() at runtime on a class that
 * does not override its hashCode() method, e.g. com.example.MyThing@6bd57cf
 */
val HeapInstance.hexIdentityHashCode: String?
  get() {
    val hashCode = identityHashCode ?: return null
    return Integer.toHexString(hashCode)
  }
