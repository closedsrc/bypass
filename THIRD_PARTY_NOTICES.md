# Third-party notices

Bypass is licensed GPL-3.0 (see [LICENSE](LICENSE)) because it links GPL-3.0 code.
The components below are the reason, and they are the only third-party code in the
app's runtime.

## libmihomo-android

- Version: 0.3.1
- Source: <https://github.com/oviron/libmihomo-android>
- Licence: GNU GPL-3.0
- Shipped as: `android/app/libs/libmihomo-android-v0.3.1.aar` (all three ABIs)

A JNI facade plus a prebuilt `libclash.so` for `arm64-v8a`, `armeabi-v7a` and
`x86_64`. It is what exposes mihomo to Kotlin (`Clash.load`, `quickSetup`,
`startTUN`, `stopTun`).

## mihomo (Clash.Meta)

- Version: bundled inside the AAR above (v1.19.28)
- Source: <https://github.com/MetaCubeX/mihomo>
- Licence: MIT, Copyright 2023 KT

The tunnel engine itself. Its GPL-3.0 wrapper is what makes this project GPL-3.0;
the core remains MIT.

## AndroidX

- `androidx.core:core-ktx`, `androidx.activity:activity-ktx`
- Source: <https://github.com/androidx/androidx>
- Licence: Apache-2.0

## Test-only

`junit:junit:4.13.2` (Eclipse Public License 1.0) is used for the JVM unit tests
and is not part of the shipped APK.
