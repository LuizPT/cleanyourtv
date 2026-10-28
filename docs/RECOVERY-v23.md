# CleanYourTV 2.4 Recovery v23

This recovery APK is built from the stable CleanYourTV 2.4 code in `custom-2.4` at commit
`92e83f6`. It retains the same launcher implementation and changes only Android version metadata
from the v21 recovery line. It contains no Family Edition behavior.

| Property | Value |
| --- | --- |
| Application ID | `com.gathin.tvhome` |
| Version code | `23` |
| Version name | `2.4-recovery-v23` |
| Minimum Android | Android 8.0 / API 26 |
| Signing certificate | Must match the Family v22 signer; verified at release |
| APK SHA-256 | Recorded with the release artifact |

## Install and recover

Install this APK over Family v22 with Android's update install (`adb install -r`) or the TV's
package installer. Keep app data; do not uninstall CleanYourTV or clear its storage. The stable
launcher retains the legacy numeric PIN and the 2.4 app preferences. Family profile files remain
stored privately but are not used by the stable build.

To return to the Family Edition after installing Recovery v23, install a Family build with
version code **24 or higher** and the same application ID and signing certificate. Version 22
cannot replace Recovery v23 because Android rejects version downgrades.

The Family controls govern only launches initiated through CleanYourTV; Android Settings, other
applications, and system-level launch paths remain outside launcher control.

## Validation record

Release size, SHA-256, certificate, JVM and instrumentation results, and the v22-to-v23 emulator
data-preservation check are recorded in the accompanying artifact-folder report after validation.
