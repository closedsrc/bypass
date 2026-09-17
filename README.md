# Bypass

One switch. Your own proxies. No US exit nodes.

Bypass is a thin, opinionated client for a Clash-compatible subscription. You import
a profile once, then the whole interface is a single switch: on routes your traffic
through the profile, off releases it. No node lists, no charts, no invented metrics.

The Android app is the working, verified deliverable. The Windows shell is a small
control service around the same filter and is honest about its limits (below).

## What it actually does

- **Load-balances across every usable node.** Traffic is sent to a generated
  `SimpleVPN` group (`type: load-balance`, round-robin) rebuilt from the profile's own
  proxies.
- **Excludes US nodes.** Proxies named `US`, `USA`, `U.S.`, `United States` or
  `America` are removed, groups lose the references to them, any group that ends up
  empty is dropped, and rules that pointed at removed nodes or groups are re-pointed
  at the generated group. Whole-word matching, so `Just Fast`, `Russia` and
  `Australia` survive.
- **Keeps the profile's routing.** Your `DOMAIN-SUFFIX`, `GEOIP`, `GEOSITE` and
  `DIRECT` rules keep their order and meaning; only the catch-all `MATCH` rule is
  re-pointed at the generated group.
- **Rewrites on lines, not on a YAML model,** so comments, key order and formatting
  in your profile come through untouched.

If every proxy in a profile is a US node, it refuses to start rather than quietly
sending traffic somewhere you did not choose.

## Repository layout

| Path | What it is |
|---|---|
| `android/` | The Android app (Kotlin, `VpnService`, mihomo over JNI). |
| `android/app/src/main/java/com/vpn/simple/ProfileFilter.kt` | The profile rewriter described above, covered by unit tests. |
| `android/app/src/main/java/com/vpn/simple/SimpleVpnService.kt` | Tunnel lifecycle: setup, TUN start, teardown, superseded commands. |
| `core/`, `cmd/` | The Go core (config parsing, filtering, HTTP control API) and its Windows host. |
| `frontend/` | The Windows shell's single-screen web UI. |
| `tools/` | Icon generator and the emulator import helper. |
| `playtest*.py` | The emulator verification harness. |

## Android

### Requirements

- JDK 17, Android SDK with platform 35 and build-tools 35.
- Gradle 8.9 (the project has no wrapper; any 8.9 install works).
- `android/local.properties` with `sdk.dir=<your SDK path>`.

The mihomo AAR is committed at `android/app/libs/libmihomo-android-v0.3.1.aar`
(39 MB, all three ABIs), so no extra download is needed.

### Build

```bash
cd android
gradle assembleDebug      # debug APK
gradle assembleRelease    # minified, resource-shrunk, R8-processed
gradle testDebugUnitTest  # 15 unit tests for the profile filter
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

## Verification

The filter has unit tests; the app itself was verified on an Android 15 emulator by
driving the real UI.

- `gradle testDebugUnitTest` — 15 tests covering US removal, group reference pruning,
  emptied-group dropping, rule re-pointing, inline `proxies: [...]` lists,
  provider-backed groups, the generated group's contents, absence of duplicate
  top-level keys, and the all-US pool refusal.
- `tools/import_profile.py <profile.yaml>` — imports a profile through the system file
  picker and connects, exactly as a user would.
- `playtest_soak.py` — six connect/disconnect cycles, three impatient taps, leaving
  and reopening the app, and a crash/ANR scan. After each connect it checks that the
  tunnel exists, the UI agrees, real traffic flows, and that the core is actually
  proxying (`match Match using SimpleVPN[...]` in the log) with nodes answering health
  checks.

Recorded run:

```
=== A. soak: 6 connect/disconnect cycles ===
  cycle 1 connect            tun=['tun0']  agents=1  UI='Connected'
     internet via tunnel: OK
     proxied flows: 6   nodes alive: 162   failed dials: 0
  cycle 2 disconnect         tun=none  agents=0  UI='Disconnected'
     direct internet: OK
  ... cycles 3-6 alternate correctly ...
=== B. impatient tapping ===  after 3 fast taps -> Connected; settled -> Disconnected
=== C. leave app while connected, come back ===  tunnel and traffic preserved
=== D. final cleanup + crash scan ===  no tunnel left, no crashes, no ANRs
PASS: soak, impatient taps and reopen all behaved; traffic verified; no crashes.
```

The scripts use constants at the top (`ADB`, `DEV`) for the adb path and the emulator
address; point them at your own device.

## Windows shell

`cmd/vpnapp` runs a local control API on `127.0.0.1:38991`, serves the web UI from
`frontend/`, and can start a bundled `mihomo` with the filtered profile.

**Limits, stated plainly:** it does not configure the Windows system proxy and does
not create a TUN adapter, so nothing is routed automatically. Traffic only reaches a
proxy if the imported profile defines a local mixed/socks port and you point apps at
it. The Go core is exercised by `go test ./...`.

Run it with `go run ./cmd/vpnapp`. The Windows engine binary is not committed; put a
`mihomo` build next to the executable (or take one from
<https://github.com/MetaCubeX/mihomo/releases>).

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
