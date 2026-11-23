# amneziaWG MN

Personal Android client based on [AmneziaWG Android](https://github.com/amnezia-vpn/amneziawg-android), upstream commit `ff15093bf3856fea0947923474870b86efb682f0` (3.1.4). Upstream history, licenses and submodule pins are preserved. See [README.upstream.md](README.upstream.md).

Current release: **1.3.0**, Android versionCode **24**, ARM64 / Android 7+. Package: `org.amnezia.awg.patched`, labeled **AmneziaWG Fix**. The original app can remain installed separately; only one copy of a VPN profile should be active.

## VPN and navigation

- Backend state changes and network reconnect sequences are serialized to avoid overlapping starts.
- Native builds use patched Go 1.25.0 with `GOTOOLCHAIN=local` and CLOCK_BOOTTIME across suspend. The v3 UAPI socket path is corrected.
- Floating navigation opens Configs, Routing and Updates.
- Routing selects all apps through VPN, only selected apps through VPN, or selected apps bypassing VPN. Search matches labels/package names; rules are saved per profile. Applying reconnects an active tunnel. The package visibility permission lists apps locally only.

## Updates and channels

**Stable** is selected by default. **Beta** requires confirmation and permits both test and stable releases. All releases use the same package/certificate and one globally increasing versionCode. Stable names are `X.Y.Z`; beta names are `X.Y.Z-beta.N`. A candidate must have both a newer SemVer and a higher versionCode. `beta.10` follows `beta.2`, and the final release follows its betas. A lower stable hotfix cannot downgrade a newer beta.

Returning Beta → Stable keeps the installed beta and its profiles until a compatible stable release exists. A channel-generation counter cancels outstanding requests and prevents a late old-channel result from being saved. Incompatible pending APKs/notifications are removed; an eligible stable APK may remain after Beta opt-in. The installer, prompts and badge recheck channel eligibility.

New clients read the public `updates/channels.json` on the `master` branch through raw.githubusercontent.com, without API tokens or REST API quota. Schema version 1 contains `stable` and `beta` entries; stable is required, beta may be null. Each entry has `tag`, `versionName`, `versionCode`, `apkUrl`, `sha256`, `notes`. Catalog requests have a 20-second total deadline and a 256 KiB limit. An invalid or unreachable catalog produces a check error, never "up to date".

Downloaded APKs must match the catalog hash/version/code, installed package and signing certificate. Partial files never reach the installer. Manual downloads show progress/cancellation and have a hard 120-second network deadline (8-second connect, 10-second idle read). Private persistent storage retains one pending APK; a newer download replaces it.

Automatic downloads are enabled by default with a daily interval. Users can disable checks or choose 15 minutes, 1/6/12/24 hours. Android JobScheduler persists jobs across reboot and waits for connectivity; battery restrictions may delay execution. Both Wi-Fi and mobile data are allowed. Notification permission is requested on Android 13+.

A ready APK produces a notification. Opening the app also offers Install/Later and shows a red dot at Updates. Android still requires install confirmation and, if needed, permission to install from this app. The installed version is displayed independently of check errors. Existing users retain auto-update settings/profiles; legacy APK cache without channel metadata is cleared on migration.

## Build

Use JDK 17 or 21, Android SDK platform 36, build-tools 36.0.0, NDK 26.1.10909125 and CMake 3.22.1.

```sh
git clone --recurse-submodules https://github.com/artemvarlapov2005/amneziaWG-MN.git
cd amneziaWG-MN
# Set sdk.dir in local.properties, or ANDROID_SDK_ROOT.
ANDROID_PACKAGE_NAME=org.amnezia.awg.patched ./gradlew :ui:assembleRelease
```

Align `ui/build/outputs/apk/release/ui-release-unsigned.apk` and sign it with the existing private release key. Keep the keystore/password outside the repository. Debug APKs (`org.amnezia.awg.debug`) cannot update release installations; the manual GitHub Actions workflow only builds development APKs.

## Publish a channel release

1. Set `amneziawgVersionName` in gradle.properties and increase `amneziawgVersionCode` above **both** catalog channel entries. Every published APK needs a new global code.
2. Build, align and sign with the existing key. Publish a GitHub Release with the version as its unique tag and `AmneziaWG-MN-<version>-arm64.apk` as its asset filename. Do not replace an APK under an existing version/tag.
3. Beta releases must be marked **Pre-release**, never Latest. Stable releases must be **Latest**, preserving update delivery to old clients that still use the GitHub latest redirect.
4. Verify the public APK URL. Generate metadata from the exact signed APK using the local script:

```sh
python3 tools/publish-update.py --channel stable \
  --apk /absolute/path/AmneziaWG-MN-1.3.0-arm64.apk \
  --build-tools /absolute/path/android-sdk/build-tools/36.0.0 \
  --notes-file /absolute/path/release-notes.txt --dry-run
```

For a beta use `--channel beta`, e.g. version/tag `1.4.0-beta.1`. Check the dry run, then repeat without `--dry-run` to update the catalog. The script checks package/ABI, version ordering/global code, signature validity and the public certificate fingerprint in `updates/signing-certificate.sha256`. That file is public metadata, not a private signing key. The script does not sign, upload or push anything.

5. Commit/publish the updated catalog **after** the Release APK exists. Only the selected channel pointer changes. Keep the other pointer. New clients use these explicit pointers rather than scanning release history.

All user updates must be delivered through GitHub Releases. Do not install builds on the user's phone through ADB.

## Validation

```sh
python3 -m unittest discover -s tools -p 'test_*.py'
./gradlew :ui:testReleaseUnitTest --tests 'org.amnezia.awg.util.*'
```

Channel implementation validation: release build, 20 selected JVM updater tests and 5 publisher tests passed. They cover numeric beta precedence, final promotion, channel filtering, downgrade rejection, generation/ABA invalidation, untrusted metadata, global publication counters, APK identity policy and bounded transfer cancellation. Full channel UI/device acceptance has not yet been run; the local SDK has no emulator/system image.

On a dedicated Android test device/emulator verify beta.1 → beta.2 → final, Stable filtering, beta opt-out waiting, and switching channels during download/install-permission flow. Exercise notification dismissal, Install/Later, badge clearing, cancellation, offline/catalog errors, wrong hash and wrong certificate. Use a test device, not an ADB installation on the user's phone.

The earlier patched ARM64 engine was installed/launched on HONOR LDY-AN00 / Android 17; a JVM harness showed 10/10 orphaned mock engines before serialization and 0/10 after it. This is not a full Android lifecycle reproduction or long-term stability guarantee. Upstream tunnel tests have two known unchanged parser failures (`INVALID_NUMBER`, `SYNTAX_ERROR`); do not describe the full repository suite as passing.
