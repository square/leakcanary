# A ContentProvider that gets created by Android on startup
-keep class leakcanary.internal.PlumberInstaller { <init>(); }

# https://www.guardsquare.com/en/products/proguard/manual/examples#enumerations
-keep public enum leakcanary.AndroidLeakFixes {
  public static **[] values();
  public static ** valueOf(java.lang.String);
}
