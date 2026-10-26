# Experimental Home bridge for Hisense Android TV 10

This is a source-review snapshot derived from [CleanYourTV](https://github.com/Gathin23/cleanyourtv),
with an optional firmware-pinned Home bridge. It leaves the original Google Home enabled and the
OS default, then redirects a verified Home transition to the replacement launcher without using an
accessibility service or intercepting remote keys.

**Status: experimental research source, not a general Hisense installation recommendation.**
No binary release or supported installer is supplied. This project is separate from the small
upstream reference launcher, which intentionally excludes background services. The source.2
changes have host checks only; earlier device acceptance does not validate this revision.

The identity decision is **research source only**: retain the original application ID for review,
publish no installable binary or installer, and make no coexistence or maintenance promise.
See [Release and identity policy](RELEASE_POLICY.md).

## Scope

The earlier `2.6-hisense-native.2` implementation was tested on one owner-identified Hisense 55U8G:

| Item | Inspected value |
|---|---|
| Vendor System Message model prefix | `55U6FUA` (not the full service code) |
| Android product model / device | `SmartTV 4K FFM` / `huangshan` |
| Vendor Software / Extended Version | `V0000.01.00U.Q0506` / `D01` |
| Android / build | Android 10, SDK 29, `QTG3.200902.001` |
| Original Home | `com.google.android.tvlauncher/.MainActivity` |
| Reviewed original Home version code | `1010943512` |

Earlier owner checks covered Home, held-volume adjustment, PS5 picture and Sonos sound, Input,
Settings, apps, casting and a controlled normal restart followed by YouTube â†’ Home. Those observations
apply to that earlier local build and configuration. This unsigned source snapshot has different
default appearance configuration and subsequent Start/lifecycle/status fixes; it has **not** been
installed or tested on the TV. It is not a claim of support for all TVs with the same marketing name
or SoC.

## What the bridge does

- Starts only after an explicit Start choice and separately granted Usage Access. Installing the app
  alone leaves the bridge off. A retained Start opt-in permits a guarded boot restart.
- Runs a visible foreground service with a Stop action. While idle it does not poll usage events.
- Calibrates the firmware's UsageEvents clock using its own service transitions. For a pending Home
  signal it checks a bounded event window and requires fresh, stable stock-Home foreground readiness.
- Sends at most one fixed own-launcher request for that Home window. It cancels on conflicting
  transitions, clock ambiguity, unsupported versions or missing prerequisites.
- Treats repeated Start requests as one attempt while starting/running. The control screen shows
  observer readiness separately from the saved startup opt-in.
- Leaves the original Home, original Input panel and TV/audio/casting/network packages intact.

Usage Access is a broad Android permission. The code's narrower use is enforced in this source,
not by a narrower permission grant. It retains current transition state in memory and does not
store usage history or send it over a network. The launcher declares no `INTERNET` permission.

This implementation refuses to start while **any accessibility service is enabled**. It is not
appropriate for someone who depends on an accessibility service; do not disable needed accessibility
features to make it run. It also requires the reviewed framework fingerprint/hash and stock Home
version. A firmware or Home-app update can make the experimental bridge stop working; the enabled
stock Home remains the fallback. No updater holds are part of this project.

## Build and review

- [Build from source](BUILD.md)
- [Architecture and permission boundaries](ARCHITECTURE.md)
- [Opt-in, Stop and restoration](RECOVERY.md)
- [Source provenance](SOURCE-PROVENANCE.json)
- [Research scope, identity and publication policy](RELEASE_POLICY.md)
- [Focused changes against the public source base](UPSTREAM_DELTA.md)

There are no wallpaper packs, themes, prebuilt APKs, private signing keys, TV backups or proprietary
stock APKs in this source candidate. The release build is unsigned. The default application ID remains
`com.gathin.tvhome`; a build signed with another key cannot update an existing installation with that
ID. Do not uninstall an existing installation merely to get around a signing mismatch.

The source derives from upstream commit `0bb7f7edbf53349c7975a29495515453ed850526`, not current main.
The earlier native2 implementation is followed by narrow preparation fixes described in
[Build](BUILD.md). Personal appearance settings were replaced with upstream defaults and an empty
legacy redirect resource needed to compile the retained helper code. Version
`2.6-hisense-native.2-source.2` and unsigned release output identify this source revision. Upstream
automatic install/debloat scripts are deliberately excluded because their Home handover is a
different design. Do not use those scripts to deploy this experiment.

CleanYourTV's [MIT notice](LICENSE) and [font licence](launcher/FONT-LICENSE-OFL.txt) are retained.
