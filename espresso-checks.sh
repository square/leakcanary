#!/bin/bash

set -e

./gradlew leakcanary-android-core:connectedCheck --stacktrace
./gradlew leakcanary-android-instrumentation:connectedCheck --stacktrace
