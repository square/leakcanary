package com.squareup.leakcanary;

import java.util.EnumSet;

/**
 * A no-op version of {@link AndroidExcludedRefs} that can be used in release builds.
 */
@SuppressWarnings("unused")
public enum AndroidExcludedRefs {
	;

	public static ExcludedRefs.Builder createAndroidDefaults() {
		return createBuilder();
	}

	public static ExcludedRefs.Builder createAppDefaults() {
		return createBuilder();
	}

	public static ExcludedRefs.Builder createBuilder(EnumSet<AndroidExcludedRefs> refs) {
		return createBuilder();
	}

	private static ExcludedRefs.Builder createBuilder() {
		return new ExcludedRefs.Builder();
	}
}