# Focused source comparison

The earlier source.2 review artifact `native-home-0bb7f7ed.patch` is against the immutable public
CleanYourTV commit `0bb7f7edbf53349c7975a29495515453ed850526`. It contains only changed/added launcher files:
24 files changed, 2042 insertions(+), 102 deletions(-). Those counts describe the earlier source.2
overlay, not this source.3 revision. Unchanged launcher files are attributed to upstream, not claimed
as our contribution.
The snapshot is not rebased onto newer main, and this is not a proposed core launcher PR.
The generated patch is kept outside the source snapshot to avoid duplicating the implementation here.

That earlier overlay was checked with `git apply --check` against a clean public-base extraction, then
applied locally and compared with every included launcher file (text line endings normalized).
This validates the patch representation; it does not validate Android behavior.

The standalone source snapshot excludes upstream automatic build/install scripts. Applying this
overlay to upstream leaves those original scripts present; their installation/Home-handover flow
has **not** been adapted or approved for this experiment. Do not run them to deploy the overlay.
Build only as described in [Build](BUILD.md); review [Research policy](RELEASE_POLICY.md) before any
separately authorized device trial. No installer or prebuilt binary is supplied here.

The earlier overlay includes the native2 clock-calibrated Home mechanism and source.2 preparation fixes,
with existing helper code retained but unconfigured. Personal appearance configuration is excluded.
The source.2 fixes have host regression coverage and no new device acceptance.

## Source.3 recovery port

This snapshot builds on research commit `6cbb2c540406872110d4e80e390ced93d4fe694d`, preserving its
idempotent Start/delivery, pending ownership, stale-failure and runtime-status fixes. It adds guarded
`START_STICKY` process recreation with a committed private budget and a recovery-only refresh of this
app's own foreground state. The refresh addresses a retained foreground flag on the reviewed firmware
that otherwise suppresses the new calibration event. Explicit Stop still saves Off and cancels recovery.
No permissions, Home policy timing, firmware pins or personal appearance configuration are broadened.

The added budget/recovery/foreground and merge-interaction JVM regressions exercise pure state and
admission seams. They do not substitute for Android lifecycle testing. A separate local recovery.2
build passed a controlled recreation trial on the reviewed TV; this merged source.3 revision has
host checks only. See [Recovery](RECOVERY.md) for observed results and the remaining Google-Home
freshness limitation. This update supplies no new upstream overlay patch or installation script.

## Earlier source.2 overlay paths

Changed or added launcher paths in the earlier overlay:

- `launcher/app/build.gradle`
- `launcher/app/src/main/AndroidManifest.xml`
- `launcher/app/src/main/java/com/gathin/tvhome/HomeKeyService.java`
- `launcher/app/src/main/java/com/gathin/tvhome/HomeNativeBootReceiver.java`
- `launcher/app/src/main/java/com/gathin/tvhome/HomeNativeControlActivity.java`
- `launcher/app/src/main/java/com/gathin/tvhome/HomeNativeService.java`
- `launcher/app/src/main/java/com/gathin/tvhome/HomeRedirect.java`
- `launcher/app/src/main/java/com/gathin/tvhome/LauncherSettingsActivity.java`
- `launcher/app/src/main/java/com/gathin/tvhome/NativeHomeAccess.java`
- `launcher/app/src/main/java/com/gathin/tvhome/NativeHomeClock.java`
- `launcher/app/src/main/java/com/gathin/tvhome/NativeHomeClockPrerequisites.java`
- `launcher/app/src/main/java/com/gathin/tvhome/NativeHomePolicy.java`
- `launcher/app/src/main/java/com/gathin/tvhome/NativeHomeSession.java`
- `launcher/app/src/main/java/com/gathin/tvhome/NativeHomeStatus.java`
- `launcher/app/src/main/res/layout/activity_launcher_settings.xml`
- `launcher/app/src/main/res/values/config.xml`
- `launcher/app/src/main/res/values/native_home_strings.xml`
- `launcher/app/src/main/res/xml/home_key_service.xml`
- `launcher/app/src/test/java/com/gathin/tvhome/HomeRedirectTest.java`
- `launcher/app/src/test/java/com/gathin/tvhome/NativeHomeClockTest.java`
- `launcher/app/src/test/java/com/gathin/tvhome/NativeHomePolicyTest.java`
- `launcher/app/src/test/java/com/gathin/tvhome/NativeHomeSessionTest.java`
- `launcher/app/src/test/java/com/gathin/tvhome/NativeHomeStartAdmissionTest.java`
- `launcher/app/src/test/java/com/gathin/tvhome/NativeHomeStatusTest.java`
