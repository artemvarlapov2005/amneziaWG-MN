# amneziaWG MN

Personal Android client based on [AmneziaWG Android](https://github.com/amnezia-vpn/amneziawg-android), upstream commit `ff15093bf3856fea0947923474870b86efb682f0` (3.1.4). Upstream history, licenses and submodule pins are preserved.

## Current changes

- Serialize backend state changes to prevent concurrent tunnel starts from overwriting the native engine handle.
- Serialize network-change reconnect sequences with a coroutine mutex.
- Build the native library with patched Go 1.25.0 and `GOTOOLCHAIN=local`, so Android timers use CLOCK_BOOTTIME across suspend.
- Fix the native UAPI socket path for the v3 module.
- ARM64 release builds install separately as `org.amnezia.awg.patched`, labeled **AmneziaWG Fix**, version `1.3.0`.

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

## Publishing a release

1. Increase `amneziawgVersionName` (for example `1.0.1`) and always increase `amneziawgVersionCode` in `gradle.properties`. Android compares the internal code, not the displayed name.
2. Build, align and sign the release APK with the existing private key.
3. Create a public GitHub Release from the corresponding source commit with tag `v1.0.1`, attach `AmneziaWG-MN-1.0.1-arm64.apk`, and mark it **Latest**. Source-code ZIP archives and debug APKs are not application updates.
4. Verify that the latest release and APK are accessible without signing in.

The updater normally reads release assets through the GitHub API. If the API is unavailable or rate limited, it follows the public `releases/latest` web redirect and uses the exact filename convention above. Keep this filename stable. No GitHub token is bundled. The downloaded APK must still pass package, signing certificate and strictly greater version-code checks.

## Per-app routing

The Routing tab selects a tunnel profile and one of three modes: all apps through VPN, only selected apps through VPN, or selected apps bypass VPN. Search matches app labels and package names. Apply saves the existing profile and reconnects it if active; other profiles and peer settings are unchanged. A non-default mode requires at least one app. Rules are local to this Android client. Android package visibility permission is used only to list installed apps locally.

## Background updates

Enabled by default, with a daily interval. Users can disable checks or select 15 minutes, 1/6/12/24 hours under Updates. Android JobScheduler persists the job across reboot and waits for network connectivity; battery restrictions can delay execution. Both Wi-Fi and mobile data are allowed. The latest public GitHub release is resolved without REST API quota.

The shared downloader verifies package, signing certificate and a strictly higher Android version code before keeping an APK or notifying. Only one download runs at a time; a newer release removes the previous pending APK. Downloads use private persistent files, not cache. A notification opens an internal installation screen, which revalidates the file and requests per-app install permission if needed. Android still requires confirmation to install.

On app entry a pending APK triggers Install/Later; the Updates icon shows a red dot while a valid newer APK is available. Notification dismissal does not remove it. Installed version is always shown separately from the check result. Notification permission is requested in-app on Android 13+. Delivery is via GitHub Releases; do not install updates to the user's phone through ADB.

## Download robustness

1.2.1 adds byte/percentage progress and cancellation to manual downloads. HTTP transport has an 8-second connect timeout, 10-second idle read timeout and a hard 120-second wait including DNS/redirects. Foreground operations also cap time spent waiting for a concurrent download. Network I/O runs in a bounded executor, so a stuck resolver cannot block UI cancellation indefinitely. Partial files have unique names and are removed after failure/cancellation; no partial APK reaches the installer.

The update interval picker keeps its dropdown interaction with an explicit opaque Material surface. Pressed, focused and selected states use the same selection colors and readable foreground, avoiding theme fallback colors.

## Stable and Beta channels

Updates defaults to **Stable**. Opting into **Beta** requires confirmation and allows both beta and stable builds. All builds use the same package/certificate and one globally increasing Android `versionCode`. Stable names are `X.Y.Z`; beta names are `X.Y.Z-beta.N`. A candidate must have both a newer SemVer and a higher code. Returning from beta to stable waits for a compatible stable release and never rolls back or deletes tunnel profiles.

New clients read `updates/channels.json` from this repository's public `master` branch via raw.githubusercontent.com, with a 20-second overall deadline and 256 KiB limit. Stable considers the stable entry; Beta considers both entries. The highest eligible SemVer wins. The JSON contains schemaVersion 1 and stable/beta entries (beta may be null); each entry specifies tag, versionName, versionCode, apkUrl, sha256, notes. The APK is checked against its metadata, installed package and certificate before notification/install. An invalid/unreachable catalog produces a check error, never "up to date".

Switching channels invalidates outstanding operations using a persisted generation counter. Incompatible cached APKs/notifications are removed. An eligible cached stable APK can remain on Beta opt-in. Manual checks, scheduled checks, prompts, badges and installer permission returns all enforce the chosen channel. Migration clears legacy APK metadata but preserves profiles, auto-update enablement and intervals.

### Publishing channel releases

1. Increase the global versionCode above **both** channel entries. Set the version name in gradle.properties, build, align and sign with the existing private release key. Do not reuse an uploaded tag or replace an APK under the same version.
2. Publish the signed APK in GitHub Releases using its version as the tag and `AmneziaWG-MN-<version>-arm64.apk` as the filename. Beta must be **Pre-release** and must not become Latest. Stable must be **Latest**, preserving updates for old clients that still use the GitHub latest redirect.
3. Verify the public APK URL is accessible. Generate metadata from the exact signed artifact, first with `--dry-run`, then without it:

```sh
python3 tools/publish-update.py --channel stable \
  --apk /absolute/path/AmneziaWG-MN-1.3.0-arm64.apk \
  --build-tools /absolute/path/android-sdk/build-tools/36.0.0 \
  --notes-file /absolute/path/release-notes.txt --dry-run
```

For a beta use `--channel beta`, e.g. version/tag `1.4.0-beta.1`. The script verifies package/ABI, the existing public signing-certificate fingerprint and monotonically increasing version/code. It performs no signing, uploads or Git pushes. Never put the private keystore/password in this repository. `updates/signing-certificate.sha256` is only a public certificate fingerprint.

4. Commit and publish the generated catalog **after** the Release APK is available. Only the selected channel pointer changes. Keep the other pointer. New clients do not scan release history or use GitHub API tokens.
5. Validate release metadata with `python3 -m unittest discover -s tools -p 'test_*.py'`. Run the updater unit tests and the real-device checklist below before calling the Android workflow fully verified.

### Android acceptance checklist

Use a dedicated test device/emulator, not an ADB installation onto the user's phone. Verify Stable does not offer beta; Beta upgrades beta.1 to beta.2 and then final; a lower stable hotfix never downgrades a newer beta; beta opt-out waits. Change the channel during a download and during the install-permission screen: no old-channel APK/notification may be committed or installed. Exercise notification dismissal, Install/Later, badge, cancellation, offline checks, wrong hash and wrong certificate. No Android test emulator is bundled with the current local SDK; JVM/tooling checks alone do not verify these UI flows.
