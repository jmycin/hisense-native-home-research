# Research source, identity and maintenance policy

This project is an experimental source snapshot for one reviewed Android 10
configuration. It is not an installer or a general Hisense launcher release.
There is no binary release, automatic update channel or supported installation
over an existing CleanYourTV app.

## Application identity

The research build deliberately retains `com.gathin.tvhome` and the original Java
namespace while the Home mechanism is reviewed. This preserves the scope of the
earlier implementation; it is **not** a coexistence strategy. A separately signed
build cannot update an existing app with that ID. Do not uninstall or clear an
existing app to work around a signing mismatch, and do not share private keys.

Release output remains unsigned. A version name identifies source revisions, not
a compatibility promise or a migration path. No private owner signing material,
device enrollment record or user configuration is part of this source.

Any independently distributed installable fork must first choose a distinct
application ID, review every own-package/receiver/clock dependency, and validate
coexistence and restoration on an agreed test device. Supplying
`launcherApplicationId` to Gradle alone does not establish that those paths work.
That identity change and a device trial are outside this preparation.

## Supported scope and maintenance

Compatibility is limited by the exact SDK, firmware/framework and stock Home
checks in the source. Keep them intact. A Home-app or firmware update may cause
the bridge to refuse and leave stock Home available; do not disable updates or
broaden pins merely to claim support.

Primary-user and time-correction checks described in [Recovery](RECOVERY.md)
remain host-preflight assumptions. There is no self-contained deployment
controller. Any enabled accessibility service makes this experiment unsupported;
needed accessibility features must not be disabled for it.

The earlier native2 implementation had acceptance on one unit. The subsequent
source packaging and preparation fixes have no new hardware acceptance. JVM
tests and a successful build do not exercise Android's full service lifecycle,
permissions, receiver delivery or the physical remote. No support for other
models, future versions or continuous maintenance is promised.

## Publication boundaries

Publish only the reviewed source manifest, focused patch, explanation and test
limits after the owner approves the public content. No images or theme package
are part of this contribution. Keep build output, private logs, proprietary APKs,
credentials and keys outside publication. Preserve the MIT and font notices.

The upstream device note is a separate small documentation proposal. The older
volume observation remains deferred: unchanged Java/XML files without the
historical configuration are inspectable evidence, not a complete reproducer.
