package com.squareup.leakcanary;

import java.io.Serializable;

public final class Exclusion implements Serializable {
  public final String name;
  public final String reason;
  public final boolean alwaysExclude;
  public final String matching;

  Exclusion(ExcludedRefs.ParamsBuilder builder) {
    this.name = builder.name;
    this.reason = builder.reason;
    this.alwaysExclude = builder.alwaysExclude;
    this.matching = builder.matching;
  }
}
