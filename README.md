# Bypass

One switch. Your own proxies. No US exit nodes.

Bypass is a thin, opinionated client for a Clash-compatible subscription. You import
a profile once, then the whole interface is a single switch: on routes your traffic
through the profile, off releases it. No node lists, no charts, no invented metrics.

Both platforms are working deliverables. Android uses `VpnService` for the tunnel; Windows
runs the same filter against a bundled mihomo core in TUN mode and ships a native
single-switch tray app (details and honest limits below).

## What it actually does

- **Load-balances across the nodes that answer.** Traffic goes to a generated `SimpleVPN`
  group (`type: load-balance`, round-robin) rebuilt from the profile's own proxies. The group
  is built with `lazy: false`, a 30 s check interval, a 4 s timeout and
  `max-failed-times: 1`, so every node is probed before it is used and a node that stops
  answering leaves the rotation on the next check instead of being discovered by a request
  timing out on it. Nodes that come back rejoin automatically.
- **Excludes US nodes.** Proxies named `US`, `USA`, `U.S.`, `United States` or
  `America` are removed, groups lose the references to them, any group that ends up
  empty is dropped, and rules that pointed at removed nodes or groups are re-pointed
  at the generated group. Whole-word matching, so `Just Fast`, `Russia` and
  `Australia` survive.

If every proxy in a profile is a US node, it refuses to start rather than quietly
sending traffic somewhere you did not choose.
- **Keeps the profile's routing.** Your `DOMAIN-SUFFIX`, `GEOIP`, `GEOSITE` and
  `DIRECT` rules keep their order and meaning; only the catch-all `MATCH` rule is
  re-pointed at the generated group.
- **Rewrites on lines, not on a YAML model,** so comments, key order and formatting
  in your profile come through untouched.
- **Leaves the apps you exclude alone.** Tick an app and it keeps using the device's own
  connection while the tunnel carries everything else — split tunnelling, for the apps
  that break behind a proxy or that need to stay on the local network. On Android those
  apps are kept out of the VPN interface entirely; on Windows they get a `PROCESS-NAME`
  rule ahead of every other rule in the profile.

If every proxy in a profile is a US node, it refuses to start rather than quietly
sending traffic somewhere you did not choose.

## Repository layout

| Path | What it is |
|---|---|
| `android/` | The Android app (Kotlin, `VpnService`, mihomo over JNI). |
| `android/app/src/main/java/com/vpn/simple/ProfileFilter.kt` | The profile rewriter described above, covered by unit tests. |
| `android/app/src/main/java/com/vpn/simple/SimpleVpnService.kt` | Tunnel lifecycle: setup, TUN start, teardown, superseded commands. |
| `android/app/src/main/java/com/vpn/simple/AppExclusions.kt` | The set of packages kept out of the tunnel, stored in app-private preferences. |
| `android/app/src/main/java/com/vpn/simple/ExcludedAppsActivity.kt` | The excluded-apps screen: every installed app, tick or untick. |
| `core/exclusions.go` | The Go equivalent: the exclusion set, its persistence, and the `PROCESS-NAME` rules it injects. |
| `core/`, `cmd/vpnapp` | The Go core: line-based filter (mirrors `ProfileFilter.kt`), TUN runtime config, mihomo lifecycle and HTTP control API; `cmd/vpnapp` is its headless host. |
| `cmd/desktop` | The native Windows client: a Fyne single-switch window that minimises to the system tray and drives `core` in-process. |
| `frontend/` | A lightweight web control UI (single switch) that can be pointed at the `cmd/vpnapp` API; the shipped Windows client does not depend on it. |
| `tools/` | Icon generator and the emulator import helper. |
| `playtest*.py` | The emulator verification harness. |

## Android

### Requirements

- JDK 17, Android SDK with platform 35 and build-tools 35.
- Gradle 8.9 (the project has no wrapper; any 8.9 install works).
- `android/local.properties` with `sdk.dir=<your SDK path>`.

**Use JDK 17.** Gradle picks up whatever `JAVA_HOME` points at, and on a JDK 21 install
the build dies in `compileDebugJavaWithJavac` — `jlink` cannot transform
`core-for-system-modules.jar`. Setting `JAVA_HOME` to a JDK 17 install fixes it:

```bash
JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" \
  gradle assembleDebug
```

The mihomo AAR is committed at `android/app/libs/libmihomo-android-v0.3.1.aar`
(39 MB, all three ABIs), so no extra download is needed.

### Build

```bash
cd android
gradle assembleDebug      # debug APK
gradle assembleRelease    # minified, resource-shrunk, R8-processed
gradle testDebugUnitTest  # 16 unit tests for the profile filter
```

The release build is signed from `android/keystore.properties`, which is **not** in
the repository:

```properties
storeFile=keystore/bypass-release.jks
storePassword=...
keyAlias=bypass
keyPassword=...
```

Without that file the release build still compiles, it just comes out unsigned, so a
fresh clone can always build a debug APK or its own release.

`assembleRelease` output: `android/app/build/outputs/apk/release/app-release.apk`
(`com.vpn.simple`, version 1.0.0, minSdk 21, targetSdk 35).

### First run

1. Launch the app and tap the switch. With no profile yet, this opens the system file
   picker; choose your Clash YAML.
2. The profile is copied into app-private storage. It is never written anywhere
   world-readable and never leaves the device.
3. Android asks for VPN consent, then the switch turns on.

**The first connect can take a while.** The core fetches its geo database (MMDB and
`GeoSite.dat`) into `<app files>/mihomo/` the first time a profile uses `GEOIP` or
`GEOSITE` rules. Later starts reuse those files and connect in a few seconds. If the
fetch cannot complete, the app gives up after two minutes and reports it rather than
sitting on "Connecting..." forever; tapping the switch during that window cancels
immediately.

### Excluded apps (split tunnelling)

The **Excluded apps** button under the switch opens the installed apps. Anything you tick
is passed to `addDisallowedApplication` when the VPN interface is built, so that app never
enters the tunnel at all: Android hands it straight to the device's own network, and its
traffic is untouched by the profile. Everything else still goes through the filtered route.

The set lives in app-private preferences and is read fresh every time the tunnel is built,
so a change made while connected rebuilds the interface straight away. Bypass itself is
not offered — excluding the app that owns the tunnel would leave it carrying nothing.

An excluded app is kept out of the VPN interface entirely, so Android routes it over the
device's own network: `ip rule show` while connected shows every UID range except the
excluded app's pointed at `tun0`, and that app's UID appearing in no `tun0` rule at all.
Unticking it puts its UID back inside the range.

## Verification

The filter has unit tests; the app itself was verified on an Android 15 emulator by
driving the real UI.

- `gradle testDebugUnitTest` — 16 tests covering US removal, group reference pruning,
  emptied-group dropping, rule re-pointing, inline `proxies: [...]` lists,
  provider-backed groups, the generated group's contents, absence of duplicate
  top-level keys, the all-US-pool refusal, and the health-check knobs on the group.
- `tools/import_profile.py <profile.yaml>` — imports a profile through the system file
  picker and connects, exactly as a user would.
- `playtest_soak.py` — six connect/disconnect cycles, three impatient taps, leaving
  and reopening the app, and a crash/ANR scan. After each connect it checks that the
  tunnel exists, the UI agrees, real traffic flows, and that the core is actually
  proxying (`match Match using SimpleVPN[...]` in the log) with nodes answering health
  checks.

Two runs, both on a MuMu Android 15 instance (1080x1920, landscape):

```
=== soak: 3 connect/disconnect cycles ===
cycle 1 CONNECT tun=True ping=True ok=0 failed=0 alive=36
cycle 2 CONNECT tun=True ping=True ok=0 failed=0 alive=28
cycle 3 CONNECT tun=True ping=True ok=0 failed=0 alive=3
   (disconnect each cycle: tun=False)
crashes: 0   ANR: 0
```

`failed=0` is the number that matters: before the group was health-checked, the same
profile produced `dial SimpleVPN ... error: context deadline exceeded` whenever
round-robin handed a request to one of the ~180 dead nodes in the pool.

Excluded apps were checked by A/B. With Otherworld Drive (`com.dfc.mobile`, UID 10061)
excluded, `ip rule show` lists `uidrange 2001-10060` and `uidrange 10062-20060` for
`tun0` — 10061 is in neither, so it goes out over `wlan0`. Unticking it puts 10061 inside
`uidrange 2001-99999`, so it goes through the tunnel.

The scripts use constants at the top (`ADB`, `DEV`) for the adb path and the emulator
address; point them at your own device.

## Windows

The Windows client is the same filter and routing model as Android, driving a bundled
`mihomo` core in **TUN mode** so traffic really is routed system-wide.

- `core/` parses and filters the profile with a **line-based rewriter that mirrors
  `ProfileFilter.kt`** — it drops USA nodes, prunes the groups' references to them, drops
  groups left empty, injects the `SimpleVPN` load-balance group, re-points rules and the
  catch-all, and adds a `dns:` block only if the profile has none. Covered by the Go
  equivalent of the Kotlin unit tests (`go test ./...`).
- `PrepareRuntime` then adds the pieces a working tunnel needs: a `tun:` block (Wintun
  adapter, `auto-route`, `dns-hijack any:53`) and a localhost `external-controller`.
- The engine is launched over that config and the app **only reports Connected once the
  control endpoint answers** (`/version`), so the state is real, not optimistic. If the
  tunnel can't form, mihomo's own output is surfaced.

### Excluded apps (split tunnelling)

Windows has no API that lists process names which are not currently running, so the
picker is built from three sources: the processes running now, everything seen on earlier
visits (remembered under `%AppData%\Bypass\known-apps.json`, capped at 500 names), and
anything already excluded — so an app you have not launched today is still there to tick.

**Excluded apps** on the main window (and on the tray menu) opens that picker. Ticking an app injects `PROCESS-NAME,<app>,DIRECT` at the top of the profile's
rules — ahead of the user's `GEOIP`/`GEOSITE` rules and the catch-all — so it is matched
before anything can send it into the tunnel. Names typed without `.exe` are given both
spellings, because that is what mihomo sees on Windows. The list is saved under
`%AppData%\Bypass\exclusions.json`, and changing it while connected restarts the tunnel so
the change takes effect now rather than at the next connect.

`find-process-mode: strict` is added to the runtime config for this: without process
lookup mihomo cannot name the app behind a connection, and those rules would never match.

One honest difference from Android: `dns-hijack any:53` still applies to the whole system,
so an excluded app's DNS is answered by mihomo (a fake IP) and only then dialled directly.
That works — mihomo maps the fake IP back to the domain — but the app's name lookups do
pass through the core, which is worth knowing if an app with its own resolver misbehaves.

### Two runtime requirements (not enforced in code)

1. **Run elevated.** Creating a Wintun adapter and rewriting the system route table needs
   administrator privileges.
2. **`wintun.dll` next to `mihomo.exe`.** The Windows engine binary and the Wintun driver
   are not committed. Get mihomo from <https://github.com/MetaCubeX/mihomo/releases> and
   Wintun from <https://www.wintun.net>, then place both beside the executable. The app
   checks and says so plainly if either is missing.

### Run

```bash
go run ./cmd/vpnapp      # headless control service on 127.0.0.1:38991

# native single-switch tray app (needs the cgo/MinGW toolchain):
go build -ldflags "-H windowsgui" -o dist/bypass-desktop.exe ./cmd/desktop
```

`cmd/desktop` is a native Fyne window that reproduces the Android single switch (the orb,
the state line, connect/disconnect, and an "Import profile" picker), and **minimises to
the system tray** — closing the window hides it; the tray menu offers Open / Connect–
Disconnect / Quit.

**Honest caveat:** the filter, the runtime-config generation and the connected-state
detection are unit- and integration-tested. The privileged parts — Windows actually
creating the Wintun adapter and carrying live traffic — were not exercised in an
automated run (they need an elevated desktop session and a real subscription) and are
verified the same way the Android build was: by driving the app by hand. The same goes
for per-app exclusion on Windows: the generated `PROCESS-NAME` rules are covered by tests,
but whether a live excluded app's traffic really leaves untouched needs that elevated
session. It is a rule-based exclusion, so it depends on mihomo resolving the process
behind each connection; the Android build, which keeps the app out of the interface
altogether, is the stronger guarantee of the two.

## Notes for anyone embedding libmihomo-android

The library's own README documents `quickSetup` as
`{"homeDir": "..."}` / `{"profile": "<path>"}`. That does not match the shipped
bridge: `InitParams` binds `json:"home-dir"` (hyphenated) plus a `version` field
carrying the Android API level, `SetupParams` accepts only `selected-map`, and the
profile is read from `<home-dir>/config.yaml`. Passing the documented keys is accepted
silently, and the core then runs its built-in default config — a tunnel that carries
traffic but routes every connection `DIRECT`, with nothing in the log saying why
beyond `[Rule] use default rules`.

`quickSetup` is also asynchronous (it runs on a goroutine), so starting the TUN as
soon as it returns races the config being applied. Both are handled in
`SimpleVpnService.connect`.

## Licence

GPL-3.0 — see [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
The GPL comes from the `libmihomo-android` JNI wrapper; the mihomo core it wraps is
MIT.
