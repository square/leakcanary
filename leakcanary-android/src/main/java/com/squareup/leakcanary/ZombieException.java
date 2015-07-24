package com.squareup.leakcanary;

public class ZombieException extends RuntimeException {

  private static final int MAX_TRACES = 5;

  public static RuntimeException wrapAsException(Autopsy autopsy) {
    if (autopsy.leakTraces.size() == 0) {
      return new RuntimeException("No zombie");
    }
    ZombieException cause = null;
    for (int i = Math.min(MAX_TRACES, autopsy.leakTraces.size()) - 1; i >= 0; ) {
      cause = new ZombieException(autopsy.leakTraces.get(i), cause);
    }
    return cause;
  }

  private ZombieException(LeakTrace leakTrace, ZombieException cause) {
    super("Grr arrh", cause);
    StackTraceElement[] trace = new StackTraceElement[leakTrace.elements.size()];
    for (int i = 0; i < leakTrace.elements.size(); i++) {
      LeakTraceElement element = leakTrace.elements.get(i);
      trace[i] =
          new StackTraceElement(element.className, element.referenceName, "Baguette.java", 42);
    }
    setStackTrace(trace);
  }
}
