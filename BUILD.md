# Building the source-review snapshot

Use a full JDK 17, Android SDK platform 34 and build-tools 34.0.0. Point `ANDROID_HOME` to the SDK or
create ignored `launcher/local.properties` containing `sdk.dir=<SDK path>` (use forward slashes on
Windows). Source and dependency versions are pinned by the included Gradle project and wrapper.

From `launcher/`, on Windows:

```powershell
.\gradlew.bat --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

On a POSIX host:

```sh
sh gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

Gradle needs to download its distribution and dependencies on the first build. `--offline` works
only after those dependencies are cached. No TV connection or ADB operation is part of this build.

The release APK is generated under `launcher/app/build/outputs/apk/release/` and is **unsigned**.
Keep build outputs out of source contributions. Signing must use a key controlled by the person
deploying it; private keys and passwords never belong in this repository. No owner signing material
or custom signing init script is needed to build or test this candidate.

## What was changed for this snapshot

The initial source.1 package retained the earlier native2 Java, manifest, control UI and test sources.
The source.2 revision added narrow preparation fixes: Start admission and service delivery are
idempotent; pending service ownership is released on destruction; stale asynchronous failures cannot
stop a newer session; and the control UI distinguishes starting, running, paused, failed and off.
It also corrects the accessibility message to describe the existing exclusion of any enabled service.
Twenty-six new JVM regressions cover the production state/admission/status seams. They do not run
Android service lifecycle, receiver delivery, permissions or remote-button integration.

Source.3 preserves those changes and ports guarded process recovery from a separately tested local
build. New pure state tests cover the persisted recreation budget, recovery/Stop serialization,
ticket ownership, and the recovery-only foreground refresh. Merge-interaction regressions exercise
these rules alongside source.2's duplicate-delivery and stale-work barriers. These tests do not run
Android's actual sticky restart scheduling or firmware UsageEvents publication.

`config.xml` uses the upstream baseline defaults plus an empty legacy
`stock_home_redirect_class` resource needed by the retained helper code. No personal theme is included.
The build version is `2.6-hisense-native.2-source.3`, and release signing is disabled. Consequently
the output is not byte-identical to the owner's accepted APK and has no device acceptance of its own.

Local host checks passed 322 JVM tests in 30 suites with no failures, errors or skips, including
38 added regressions relative to source.2. Android lint completed with zero errors, 64 warnings and
99 informational findings; unsigned release assembly succeeded. These are host results, not device
acceptance of the merged source.3 snapshot.

The commands use a cached JDK 17/SDK 34 environment and
`--offline`; this is not proof of a first-time online dependency resolution. Receipts are kept outside
this source candidate for review. A later device trial needs separate owner authorization and
hardware acceptance after source/permission review. This packet proposes no binary release.
