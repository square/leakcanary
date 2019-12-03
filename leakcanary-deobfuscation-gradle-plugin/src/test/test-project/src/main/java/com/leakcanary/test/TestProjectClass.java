package com.leakcanary.test;

public class TestProjectClass {
  static class Leak {
    Object o = new Object();
  }

  static Leak leak = new Leak();

  public void foo() {
    leak.o = this;
  }
}
