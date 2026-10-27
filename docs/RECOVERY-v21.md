# CleanYourTV 2.4 Recovery v21

This recovery build uses the stable CleanYourTV 2.4 implementation from `custom-2.4` at commit
`92e83f6`. The recovery branch is based on the existing stable v19 recovery line. Its only Android
configuration change from that line is the version metadata; it does not include Family Edition
code or alter launcher behavior.

| Property | Value |
| --- | --- |
| Application ID | `com.gathin.tvhome` |
| Version code | `21` |
| Version name | `2.4-recovery-v21` |
| Minimum Android | Android 8.0 / API 26 |
| APK size | 305,605 bytes |
| APK SHA-256 | `54a16101a47d8dc27b330c96e6001485c78ec5e0fe9f2d9c2ddf5aed4eea9645` |
| Signing certificate SHA-256 | `e78cac3a9a5cb50ce96c419b8facca08e9f99bda3e45c9849656f550b78ee6a0` |

## Installing and recovering

Install the Family v20 APK over Family v18 with `adb install -r`, then use Recovery v21 if a
rollback is needed. Keep the same application ID and signing certificate. Do not uninstall the
launcher or clear its app data.

The v20 to v21 update was validated on an Android API 34 emulator using `adb install -r`. The
SHA-256 digests of `launcher-state.properties` and `family-profiles.properties` were identical
before and after the update. The legacy PIN salt and hash properties remained present. No
uninstall, data-clear command, or app launch was used during the file comparison. The debug build
with the same application ID, version code, code and signer was used only to read the private files;
the emulator was then returned to Recovery v21 Release.

The stable v2.4 code retains and verifies the legacy PIN. It does not use Family Edition settings,
but the update leaves `family-profiles.properties` in the private app data directory. Those Family
settings become available again only after installing a Family Edition build with version code
**22 or higher** and the same signing certificate.

## Validation

- Recovery v21: `testReleaseUnitTest` — 54 passed; `assembleRelease` — passed with JDK 17 and SDK 34.
- Family v20: `testReleaseUnitTest` — 132 passed; release build — passed.
- Family v20 UI instrumentation on the explicit API 34 emulator — 14 passed, including correct and
  incorrect sequences, cancellation, adult re-entry timing, D-pad navigation, and selector
  preservation. No physical TV was used.
- Recovery v21 metadata and signer were checked with Android build-tools `aapt` and `apksigner`.
  The certificate matches the v20 APK, Family v18, Recovery v19, and stable v4.

The published v20 APK remains unchanged at 409,481 bytes (119 bytes below the CI limit of 409,600).
Comparison with Family v18 shows the v20 modal accounts for about 1,228 compressed DEX bytes and
480 resource-table bytes; no duplicate image assets or new libraries were found. No optimization
was made because a material saving would require changing the tested modal or its accessible
resources. A local reproducibility build had the same size and identical ZIP entry contents, but a
different APK SHA-256, so it was not substituted for the published artifact.

## GitHub Actions observation

The fork reports Actions enabled and the CI workflow active. The Family branch's workflow includes
pushes to `feature/family-profiles`, but the GitHub API returned no run or check suite for commit
`cda68fcb7f37532a102dc452de5b40c2efdea401`, and the repository event list contains no PushEvent
for that SHA. The evidence points to the branch update not producing a normal push trigger; it does
not show a failing CI job. The default branch is `custom-2.4` and its current workflow push filter
only lists `main`. No workflow or stable branch was changed for this investigation.

The launcher-level parental controls only cover actions initiated through CleanYourTV; Android
Settings, other applications, and system-level ways to open content remain outside their scope.
