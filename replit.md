# AndroidUSBCamera (AUSBC)

A Kotlin/Android library for UVC (USB camera) support on Android devices. Supports multi-camera, OpenGL ES rendering, photo/video capture, and audio recording.

## Project Structure

| Module | Purpose |
|--------|---------|
| `libausbc/` | Main Kotlin library — UVC camera engine |
| `libnative/` | Native C++ layer (CMake build) |
| `libuvc/` | UVC JNI bridge (ndk-build) |
| `app/` | Demo application |
| `libnative/aar/` | Pre-compiled native AAR (libnative-3.2.9.aar) |
| `libuvc/aar/` | Pre-compiled UVC AAR (libuvc-3.2.9.aar) |

## Build Requirements

- **Java 17** (or 11)
- **Android SDK** — compileSdkVersion 34, buildTools 34.0.0
- **Android NDK** — version 21.0.6113669 (required for native modules)
- **Gradle** 6.7.1 (via wrapper `./gradlew`)

## How to Build

### Via GitHub Actions (recommended)

The repo has two workflows in `.github/workflows/`:

- **`android-build.yml`** — triggers on push to `master` or manually; builds a debug APK
- **`build.yml`** — manual trigger; builds debug APK

To trigger manually: GitHub → Actions → "Build AndroidUSBCamera" → Run workflow.

### Locally / on a machine with full Android SDK

```bash
# Create local.properties pointing to your SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties
echo "ndk.dir=/path/to/android-sdk/ndk/21.0.6113669" >> local.properties

chmod +x gradlew
./gradlew :app:assembleDebug
```

## Pre-built Artifacts

- `app/release/app-release.apk` — pre-built release APK included in the repo
- `libnative/aar/libnative-3.2.9.aar` — pre-compiled native library
- `libuvc/aar/libuvc-3.2.9.aar` — pre-compiled UVC library

## Why Replit Can't Build This Directly

The Android NDK (required for native C++ compilation) is ~1 GB compressed. Replit's disk is consumed by the Android SDK platform tools. Use GitHub Actions (already configured) or a local machine with Android Studio for full builds.

## User Preferences
