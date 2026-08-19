#!/bin/bash
# Common env for WSL-based Gradle invocations — sourced by build scripts
# and by CI, so the two stay in sync instead of drifting.
export PATH=/opt/gradle/gradle-8.9/bin:"$PATH"
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/27.0.12077973
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
