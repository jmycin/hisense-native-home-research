# Architecture and review boundaries

The original Google launcher stays the enabled default Home and continues to provide its original
Inputs panel. The replacement's optional foreground service observes the system Home-close-dialog
signal, calibrates the firmware's UsageEvents clock using its own foreground-service transitions,
and checks for a fresh stock `MainActivity` resume before one fixed request to open its own launcher.
The app is not a device owner, root process or accessibility key filter.

`NativeHomePolicy`, `NativeHomeClock` and `NativeHomeSession` are pure state/policy classes covered by
JVM tests. A cancellation or Stop invalidates the current generation. The two-second pending-Home
window and startup calibration have bounded retries; idle operation has no polling loop. A request
already sent before Stop cannot be recalled.

Start admission checks prerequisites before reserving a ticket. Repeated requests while pending or
active preserve that ticket and any Home generation. The service claims each pending delivery once;
pending ownership is tracked so destruction can release it. Startup and Home-window failure handlers
run inside the matching session barrier, so an old query failure cannot stop a newer attempt.
The control screen reads session/runtime/clock state rather than treating the saved boot opt-in as
proof of a running observer. Its 250 ms display refresh exists only while that screen is resumed and
does not query UsageEvents. These preparation changes have JVM coverage, not new Android integration
or device acceptance.

`NativeHomeAccess` checks Usage Access, unlocked-user state, stock Home version and its enabled/default
activity, and refuses any enabled accessibility service. `NativeHomeClockPrerequisites` additionally
requires Android 10, the exact reviewed fingerprint and `/system/framework/services.jar` hash. It
reads `persist.debug.time_correction`; it never writes that property. Its empty/true check must not
be treated as a generic firmware-discovery method: supported hosts must verify the property view
and framework before a new trial. Do not remove or broaden pins just to run on another TV.
The original host controller also required current Android user 0. That check is a host-preflight
requirement; this copied application code does not itself enforce user 0.

The app's receiver for the Home signal requires the sender's `STATUS_BAR_SERVICE` permission.
`HomeNativeService` and `HomeNativeBootReceiver` are not exported. The shell control activity is
exported but requires `WRITE_SECURE_SETTINGS`; the launcher does not request that permission itself.
No controller grants permissions or changes system settings merely by opening the UI.

Normal Android `FOREGROUND_SERVICE` and `RECEIVE_BOOT_COMPLETED` permissions support the visible
observer and previously opted-in boot restart. `PACKAGE_USAGE_STATS` additionally requires the
user's separate Usage Access choice. That grant is broad; bounded event queries, memory-only state
and no-network behavior are implementation properties, not restrictions imposed by the grant.

The stock app-open route is undocumented and firmware/app-version dependent. No arbitrary
protected-component launch, data disclosure or privilege escalation was demonstrated by the earlier
work. That does not replace a security review. Before publication, reviewers should trace receiver
resolution, sender identity, fixed target validation, Stop races, permission boundaries and data flow.
Any demonstrated trust-boundary vulnerability should be reported privately to the affected project
or vendor before operational details are published.

The code has no direct background `startActivity` fallback, overlay cover, arbitrary input URI,
wallpaper download or updater disable. It is an experimental derivative rather than a proposed core
CleanYourTV feature, because the upstream reference launcher excludes background services.
