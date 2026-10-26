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
This source.2 revision adds narrow preparation fixes: Start admission and service delivery are
idempotent; pending service ownership is released on destruction; stale asynchronous failures cannot
stop a newer session; and the control UI distinguishes starting, running, paused, failed and off.
It also corrects the accessibility message to describe the existing exclusion of any enabled service.
Twenty-six new JVM regressions cover the production state/admission/status seams. They do not run
Android service lifecycle, receiver delivery, permissions or remote-button integration.

`config.xml` uses the upstream baseline defaults plus an empty legacy
`stock_home_redirect_class` resource needed by the retained helper code. No personal theme is included.
The build version is `2.6-hisense-native.2-source.2`, and release signing is disabled. Consequently
the output is not byte-identical to the owner's accepted APK and has no device acceptance of its own.

Local host checks passed 284 JVM tests in 27 suites with no failures, errors or skips; lint and
unsigned release assembly completed. The commands used a cached JDK 17/SDK 34 environment and
`--offline`; this is not proof of a first-time online dependency resolution. Receipts are kept outside
this source candidate for review. A later device trial needs separate owner authorization and
hardware acceptance after source/permission review. This packet proposes no binary release.
