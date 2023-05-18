package shark.internal;

import okio.ByteString;

class ByteStringCompat {

  /**
   * This is deprecated (error) to invoke from Kotlin but invoking the Kotlin extension function
   * leads to improper bytecode that goes through the companion class.
   */
  static ByteString encodeUtf8(String string) {
    return ByteString.encodeUtf8(string);
  }
}
