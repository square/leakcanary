package com.squareup.leakcanary

import com.android.tools.perflib.captures.DataBuffer
import java.io.UnsupportedEncodingException

class FakeDataBuffer(private val stringCharset: String = PRE_O_CHARSET) : DataBuffer {

  private var intsToRead: IntArray? = null
  private var intIndex = -1
  private var stringsToRead: Array<out String?>? = null
  private var stringIndex = -1

  fun setIntsToRead(vararg ints: Int) {
    intsToRead = ints
    intIndex = 0
  }

  fun setStringsToRead(vararg strings: String?) {
    stringsToRead = strings
    stringIndex = 0
  }

  override fun readByte(): Byte {
    throw UnsupportedOperationException("no bytes to read")
  }

  override fun dispose() {}

  override fun read(bytes: ByteArray) {
    throw UnsupportedOperationException("no bytes to read")
  }

  override fun readSubSequence(
    bytes: ByteArray,
    start: Int,
    length: Int
  ) {
    if (stringsToRead == null || stringIndex < 0 || stringIndex >= stringsToRead!!.size) {
      throw UnsupportedOperationException("no bytes to read")
    }

    val s = stringsToRead!![stringIndex++]
    try {
      System.arraycopy(s!!.toByteArray(charset(stringCharset)), start, bytes, 0, length)
    } catch (e: UnsupportedEncodingException) {
      throw UnsupportedOperationException(e)
    }
  }

  override fun readChar(): Char {
    throw UnsupportedOperationException("no bytes to read")
  }

  override fun readShort(): Short {
    throw UnsupportedOperationException("no bytes to read")
  }

  override fun readInt(): Int {
    if (intsToRead == null || intIndex < 0 || intIndex >= intsToRead!!.size) {
      throw UnsupportedOperationException("no bytes to read")
    }
    return intsToRead!![intIndex++]
  }

  override fun readLong(): Long {
    throw UnsupportedOperationException("no bytes to read")
  }

  override fun readFloat(): Float {
    throw UnsupportedOperationException("no bytes to read")
  }

  override fun readDouble(): Double {
    throw UnsupportedOperationException("no bytes to read")
  }

  override fun setPosition(l: Long) {}

  override fun position(): Long {
    throw UnsupportedOperationException("no bytes to read")
  }

  override fun hasRemaining(): Boolean {
    throw UnsupportedOperationException("no bytes to read")
  }

  override fun remaining(): Long {
    throw UnsupportedOperationException("no bytes to read")
  }

  companion object {
    private val PRE_O_CHARSET = "UTF-16BE"
  }
}