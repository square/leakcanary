package com.squareup.leakcanary;

import com.squareup.haha.perflib.io.HprofBuffer;

import java.io.UnsupportedEncodingException;
import java.util.List;

public final class FakeHprofBuffer implements HprofBuffer {
  private static final String PRE_O_CHARSET = "UTF-16BE";

  private final String stringCharset;

  private List<Byte> byteList;
  private List<byte[]> byteArrayList;

  private int[] intsToRead;
  private int intIndex = -1;
  private String[] stringsToRead;
  private int stringIndex = -1;

  FakeHprofBuffer() {
    this(PRE_O_CHARSET);
  }

  FakeHprofBuffer(String stringCharset) {
    this.stringCharset = stringCharset;
  }

  public void setIntsToRead(int... ints) {
    intsToRead = ints;
    intIndex = 0;
  }

  public void setStringsToRead(String... strings) {
    stringsToRead = strings;
    stringIndex = 0;
  }

  @Override
  public byte readByte() {
    throw new UnsupportedOperationException("no bytes to read");
  }

  @Override
  public void read(byte[] bytes) {
    throw new UnsupportedOperationException("no bytes to read");
  }

  @Override
  public void readSubSequence(byte[] bytes, int start, int length) {
    if (stringsToRead == null || stringIndex < 0 || stringIndex >= stringsToRead.length) {
      throw new UnsupportedOperationException("no bytes to read");
    }

    String s = stringsToRead[stringIndex++];
    try {
      System.arraycopy(s.getBytes(stringCharset), start, bytes, 0, length);
    } catch (UnsupportedEncodingException e) {
      throw new UnsupportedOperationException(e);
    }
  }

  @Override
  public char readChar() {
    throw new UnsupportedOperationException("no bytes to read");
  }

  @Override
  public short readShort() {
    throw new UnsupportedOperationException("no bytes to read");
  }

  @Override
  public int readInt() {
    if (intsToRead == null || intIndex < 0 || intIndex >= intsToRead.length) {
      throw new UnsupportedOperationException("no bytes to read");
    }
    return intsToRead[intIndex++];
  }

  @Override
  public long readLong() {
    throw new UnsupportedOperationException("no bytes to read");
  }

  @Override
  public float readFloat() {
    throw new UnsupportedOperationException("no bytes to read");
  }

  @Override
  public double readDouble() {
    throw new UnsupportedOperationException("no bytes to read");
  }

  @Override
  public void setPosition(long l) { }

  @Override
  public long position() {
    throw new UnsupportedOperationException("no bytes to read");
  }

  @Override
  public boolean hasRemaining() {
    throw new UnsupportedOperationException("no bytes to read");
  }

  @Override
  public long remaining() {
    throw new UnsupportedOperationException("no bytes to read");
  }
}
