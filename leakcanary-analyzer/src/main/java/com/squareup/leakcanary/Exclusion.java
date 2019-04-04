package com.squareup.leakcanary;

import java.io.Serializable;

public final class Exclusion implements Serializable {
  public final String name;
  public final String reason;
  public final boolean alwaysExclude;
  public final String matching;

  Exclusion(ExcludedRefs.ParamsBuilder builder) {
    this.name = builder.getName();
    this.reason = builder.getReason();
    this.alwaysExclude = builder.getAlwaysExclude();
    this.matching = builder.getMatching();
  }
}
