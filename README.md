# amneziaWG MN

Personal Android client based on [AmneziaWG Android](https://github.com/amnezia-vpn/amneziawg-android), upstream commit `ff15093bf3856fea0947923474870b86efb682f0` (3.1.4). Upstream history, licenses and submodule pins are preserved.

## Current changes

- Serialize backend state changes to prevent concurrent tunnel starts from overwriting the native engine handle.
- Serialize network-change reconnect sequences with a coroutine mutex.
- Build the native library with patched Go 1.25.0 and `GOTOOLCHAIN=local`, so Android timers use CLOCK_BOOTTIME across suspend.
- Fix the native UAPI socket path for the v3 module.
- ARM64 release builds install separately as `org.amnezia.awg.patched`, labeled **AmneziaWG Fix**, version `3.1.4-mn4`.

## Navigation and updates

The floating bottom bar switches between Configs and Updates. Updates use the latest public GitHub Release and ARM64/universal APK assets. Installation checks the package, signing certificate and a strictly higher Android version code. Publish signed release APKs with the same private signing key; debug APKs cannot update the installed release. Android asks the user to authorize installation from this app.

## Build

Use JDK 17 or 21, Android SDK platform 36, build-tools 36.0.0, NDK 26.1.10909125 and CMake 3.22.1.

```sh
git clone --recurse-submodules https://github.com/artemvarlapov2005/amneziaWG-MN.git
cd amneziaWG-MN
# Configure sdk.dir in local.properties, or set ANDROID_SDK_ROOT.
ANDROID_PACKAGE_NAME=org.amnezia.awg.patched ./gradlew :ui:assembleRelease
```

The release output is `ui/build/outputs/apk/release/ui-release-unsigned.apk`. Align and sign it with your private signing key using Android build-tools. Keep that key outside this repository: future updates of the patched package require the same certificate.

For a development APK use `./gradlew :ui:assembleDebug`; its package is `org.amnezia.awg.debug`. A manual GitHub Actions workflow builds this development APK without signing secrets.

## Validation and remaining work

The shipped ARM64 APK was built, signed, installed and launched on HONOR LDY-AN00 / Android 17. The native clock was checked in the compiled binary. A JVM harness with Android/native stubs demonstrated an orphaned engine in 10/10 overlapping starts before serialization and 0/10 after it. This is not a full Android lifecycle reproduction or a long-term VPN stability guarantee.

Upstream `:tunnel:testReleaseUnitTest` currently passes 8/10 tests; `INVALID_NUMBER` and `SYNTAX_ERROR` assertions fail in unchanged configuration parser code/fixtures. Track these separately; do not describe the full suite as passing.

VPN profiles and APK signing keys are intentionally not included. Export a profile from the original app and import it into the patched app; only one copy of that profile should be active.

See [upstream README](README.upstream.md) for original project information.
