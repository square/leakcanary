package com.leakcanary.test;

public class TestProjectClass {
  static class Leak {
    static Object o = new Object();
  }
  public void foo() {
    Leak.o = new Object();
  }
}
