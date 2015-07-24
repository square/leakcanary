package com.squareup.leakcanary;

public class OOMErrorHandler implements Thread.UncaughtExceptionHandler {

  public static void install(Doctor doctor) {
    Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    OOMErrorHandler oomErrorHandler = new OOMErrorHandler(defaultHandler, doctor);
    Thread.setDefaultUncaughtExceptionHandler(oomErrorHandler);
  }

  private final Thread.UncaughtExceptionHandler defaultHandler;
  private final Doctor doctor;

  public OOMErrorHandler(Thread.UncaughtExceptionHandler defaultHandler, Doctor doctor) {
    this.defaultHandler = defaultHandler;
    this.doctor = doctor;
  }

  @Override public void uncaughtException(Thread thread, Throwable throwable) {
    if (isOutOfMemoryError(throwable)) {
      // TODO Pass in OOM message as cause?
      doctor.performDiagnostic();
    }
    defaultHandler.uncaughtException(thread, throwable);
  }

  private boolean isOutOfMemoryError(Throwable throwable) {
    while(throwable != null) {
      if (throwable instanceof OutOfMemoryError) {
        return true;
      }
      throwable = throwable.getCause();
    }
    return false;
  }
}
