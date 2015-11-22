package com.squareup.leakcanary;

import java.io.Serializable;

/**
 * A no-op version of {@link ExcludedRefs} that can be used in release builds.
 */
public class ExcludedRefs implements Serializable {

	@Override
	public String toString() {
		return "Empty class for no-op";
	}

	@SuppressWarnings("unused")
	public static final class Builder {

		public Builder instanceField(String className, String fieldName) {
			return this;
		}

		public Builder instanceField(String className, String fieldName, boolean always) {
			return this;
		}

		public Builder staticField(String className, String fieldName) {
			return this;
		}

		public Builder staticField(String className, String fieldName, boolean always) {
			return this;
		}

		public Builder thread(String threadName) {
			return this;
		}

		public Builder thread(String threadName, boolean always) {
			return this;
		}

		public Builder clazz(String className) {
			return this;
		}

		public Builder clazz(String className, boolean always) {
			return this;
		}

		public ExcludedRefs build() {
			return new ExcludedRefs();
		}
	}
}
