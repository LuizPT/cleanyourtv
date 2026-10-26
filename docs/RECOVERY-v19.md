# CleanYourTV 2.4 Recovery v19

This recovery APK is built from the stable CleanYourTV 2.4 implementation in `custom-2.4` at
`92e83f6`. The recovery branch changes only Android version metadata; it does not add or remove
launcher behavior. It retains the original parental PIN implementation.

| Property | Value |
| --- | --- |
| Application ID | `com.gathin.tvhome` |
| Version code | `19` |
| Version name | `2.4-recovery-v19` |
| Minimum Android version | Android 8.0 / API 26 |
| Signing certificate SHA-256 | `e78cac3a9a5cb50ce96c419b8facca08e9f99bda3e45c9849656f550b78ee6a0` |

## Install over Family v18

Use the v19 APK with the same signing certificate as the installed Family v18 APK. Install it as an
update, for example with `adb install -r CleanYourTV-2.4-Recovery-v19.apk`. Do not uninstall the
launcher or clear its data. Android then keeps the app's private data directory, including the
existing `launcher-state.properties` parental PIN hash and family preference/history files. The
stable 2.4 code may not use Family Edition settings, but this recovery build does not delete or
rewrite those files.

The recovery build's retained PIN hash is the original v2.4 credential. It does not migrate or
replace that credential. The PIN is stored as a salted PBKDF2 hash, not as readable digits.

## Validation

- `testReleaseUnitTest`: 54 tests passed.
- `assembleRelease`: passed with JDK 17 and Android SDK 34.
- On an API 34 emulator, installed the v18 release and then this v19 release using `adb install -r`.
  No uninstall or data clear was used. The private launcher PIN state and family settings/history
  file had identical SHA-256 values before and after the update; the PIN salt/hash properties were
  present both times. A matching v19 debug build was used only to read back app-private file hashes,
  then the release APK was reinstalled. The Xiaomi TV was not used for this test.
- The recovery signer was verified against both Family v18 and Recovery v17.

## Return to Family Edition

After installing Recovery v19, Android will reject an APK whose version code is 18 or lower as a
downgrade. Install a signed Family Edition build with version code **20 or higher** to return to the
newer Family application while keeping the same package and app data.

The launcher-level parental PIN only controls actions initiated through CleanYourTV; it does not
restrict Android Settings, other apps, or system-level ways to open content.
