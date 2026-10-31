# Opt-in, Stop and restoration

These are source-review instructions, not authorization to install or operate this snapshot on
someone's TV. Review the code and support checks, back up the current package/default-Home/accessibility
and Usage Access state, and obtain the owner's agreement before a device trial. No root, firmware
flashing, factory reset, system-package uninstall or update hold is needed by this design.

## Before an optional trial

Require current Android user 0 in the authorized host preflight (`am get-current-user`). That was
a host-controller requirement in the earlier trial, not an additional user check inside this app.
Keep original `com.google.android.tvlauncher/.MainActivity` enabled and the OS default. Verify its
version, original Input panel, framework pin and `persist.debug.time_correction` using read-only checks.
The reviewed host value for `persist.debug.time_correction` was empty/default or `true`; stop if
it is `false`, another value or unreadable. Do not write a property to pass that check or disable
vendor services. Keep Cast, Play services, Store,
remote/Bluetooth/audio/HDMI services intact. This implementation refuses any enabled accessibility
service; a TV whose user needs accessibility is outside its supported use.

Open the launcher normally first. Its Launcher settings contain **Native Home trial**, with
**Usage Access**, **Start** and **Stop** controls. Grant Usage Access to this app only if the owner
accepts its broad permission and the narrow implementation described in [Architecture](ARCHITECTURE.md).
Then choose Start explicitly. That retained opt-in also permits a guarded restart after a TV reboot
or an Android-initiated service recreation after process death.
The foreground notification displays that the trial is on and offers Stop.
The control screen separately reports starting, running, paused, failed or off; Start is unavailable
while an attempt is starting or running. A saved opt-in with no active observer is paused, not proof
that Home redirection works. Repeated Start does not queue another observer or reset calibration.

The permission-protected ADB control activity is for authorized local maintenance. It can display
the same controls; the optional string extra `operation` accepts `start` or `stop`. No broad
installation/debloat controller, private device baseline or automatic permission-grant script is
included in this candidate. Keep debugging private and authorized during a trial; its final
debugging policy is a separate owner choice.

## Stop and prove the opt-out

1. Choose Stop in the trial UI or its notification. Under the session monitor, Stop cancels pending
   work, attempts to cancel Android's service restart, and saves the opt-in as false. It does not
   itself force the current app to stock Home.
2. Verify the saved-off state and absent service before revoking access, force-stopping or rebooting.
   An authorized diagnostic dump of `HomeNativeControlActivity` exposes
   `HomeNativeTrial stopDurable=true stopError=none`. A service dump should show no running
   `HomeNativeService`. A timeout or process death alone is not proof of a durable Stop.
3. If the UI reports that the off choice could not be saved, keep the TV awake and retry Stop;
   do not call the restoration complete or reboot while persistence is unverified.
4. Press Home: because the original default was preserved, normal stock Home behavior should resume.
   Revoke this app's Usage Access if returning to the prior ungranted state, then verify it separately.

## Automatic recovery limits

Accepted service starts use `START_STICKY`, allowing Android to recreate a killed process. A null
delivery must pass the retained opt-in and ordinary compatibility/permission checks and consume a
committed recovery admission before starting a fresh observer and calibration. It cannot turn an
Off choice back On. Android controls restart scheduling; this is not a guarantee against every OEM
process policy, crash, or low-memory condition. Force-stop is intentionally different from this
recreation path and must not be used as proof that recovery failed.

Recovery permits at most two process-recreation admissions in an anchored ten-minute window. Boot
receiver starts are not charged to that count. Three private
startup values record the boot, elapsed window start and count, not app usage. Incomplete, invalid,
unreadable or uncommittable budget state refuses automatic recovery. A newly admitted explicit
UI/shell Start may reset the limit. Boot and null-intent deliveries do not reset a same-window
budget. A new boot or expired window can admit a new window; expiry itself schedules no retry.
If the observer stays absent, open the trial controls, inspect the status, and explicitly choose
Start when ready. Do not repeatedly force-stop it or clear app data to bypass the budget.

Revoking Usage Access alone is different from saving Stop. Restore only the exact preferences
changed by the trial; do not overwrite whole settings dictionaries or reset the TV.
An immediate Stop then Start while the old observer is tearing down can fail closed; wait for teardown
and retry. The implementation deliberately does not reuse the old observer or clock.

## Acceptance and limitations

Use a single owner checklist after automated checks: YouTube → Home, physical Input and Settings,
held volume at a comfortable level, PS5 picture/Sonos sound, casting and a normal TV restart followed
by Home. Check Home from the screensaver separately. Lifecycle timestamps do not measure the first
visible frame or prove all intermittent stock-Home flashes are gone.
A stale in-flight clock confirmation can still invalidate that observer's clock and make a later
generation fail closed. The retained source.2 barriers prevent stale Stop/error writes; they do not promise
uninterrupted recovery from clock errors.

If Google Home is already visible, another Home press can leave it there despite a healthy observer:
the policy requires a fresh stock-Home resume and intentionally refuses stale foreground evidence.
Open the app named **Home** from Google's Apps tab to return to the replacement. This needs no ADB,
reinstall or permission reset. Disabling stock Home or loosening foreground guards is not a fix.

The earlier native2 implementation had owner acceptance and a controlled restart on one unit. A later
local recovery.2 build on that unit passed the following automated observations:

- One controlled crash of this app's process followed by Android service recreation and fresh clock
  readiness in about seven seconds, without a manual Start, dialog action or activity launch to revive it.
- Home from Settings, Inputs and YouTube, repeated after recreation; durable Stop left the service
  absent and stock Home available after a fifteen-second observation.
- One normal reboot: after the owner restored Wi-Fi, the first reconnected snapshot already showed
  the observer ready before any maintenance Start. Wi-Fi continuity was not demonstrated.
- Home from a real screensaver started with the TV's **Start now** action. This was not a wait for
  the configured inactivity timeout.

Those observations validate that separate local build and the observed recreation path, not every
kind of process kill. The original unexpected process-termination cause remains unknown. Physical
PS5/audio/HDR, held-volume, remote hardware and phone casting were not re-tested in that recovery
trial; their earlier acceptance must not be presented as fresh recovery-build acceptance.

The unsigned/default-config source.3 snapshot ports that mechanism onto source.2's separate
Start/lifecycle/status fixes. This combined revision has not had a device install or recovery drill.
Do not present its local JVM/build results as hardware acceptance or broader model compatibility.
