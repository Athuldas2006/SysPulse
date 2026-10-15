# SysPulse Android source

This directory contains the Android Studio project for SysPulse.

## Build

Open this `android/` directory in Android Studio and allow Gradle to sync. The project uses the Gradle wrapper; on Windows, run `gradlew.bat assembleDebug` from this directory. The generated APK will be written under `app/build/outputs/apk/`.

The Android SDK path is machine-specific and belongs in `local.properties`, which is intentionally ignored by Git.
