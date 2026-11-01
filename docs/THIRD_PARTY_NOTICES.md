# Credits and license notices

## Receiver

DiPlay is a modified version of [xcertplay by shilapi](https://github.com/shilapi/xcertplay). The upstream receiver is licensed under GNU GPL version 3; the full text is in `LICENSE` and the original README is retained in `docs/UPSTREAM-README.md`.

Upstream credits [LIVI](https://github.com/f-io/LIVI) and [Showcase](https://github.com/amineross/showcase) for protocol research. Existing source comments and attribution are preserved.

## Home and settings UI

`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` adapts the palette, visual arrangement and interface copy of the [DiAuto project](https://github.com/shihabal3amri/DiAuto). DiAuto's source is licensed under AGPL version 3. The UI file is marked AGPL-3.0-only; its license text is included in `docs/licenses/DiAuto-AGPL-3.0.txt`.

## CarPlay icon

The unmodified icon was obtained from Apple's developer site at:

https://developer.apple.com/assets/elements/icons/carplay/carplay-96x96_2x.png

CarPlay and the CarPlay icon are Apple Inc. marks/assets. This asset is not covered by the project's open-source code license. Its use here does not imply Apple approval or certification.

## Runtime dependencies

- AndroidX and Jetpack Compose — Android Open Source Project; Apache License 2.0.
- Kotlin standard library — JetBrains; Apache License 2.0.
- Bouncy Castle 1.79 — The Legion of the Bouncy Castle Inc.; Bouncy Castle license (MIT-style).
- JmDNS 3.6.3 — JmDNS contributors; Apache License 2.0.
- SLF4J — QOS.ch; MIT license.

Gradle dependency declarations and version catalog accompany the source. License files available in the resolved artifacts are included under `docs/licenses/dependencies/`.

## Native call echo cancellation

DiPlay vendors a subset of [SpeexDSP 1.2.1](https://github.com/xiph/speexdsp/tree/SpeexDSP-1.2.1) from Xiph.org Foundation and its contributors, including the acoustic echo canceller, preprocessor and FFT/filterbank support. It is compiled into `libspeex_echo.so` for the optional experimental **Call echo cancellation** feature.

SpeexDSP uses a BSD-style three-clause license. The full copyright notices, redistribution conditions and disclaimer are retained in [the vendored COPYING file](../shared/src/main/jni/speexdsp/COPYING). Component source headers retain their additional notices, including KISS FFT's Mark Borgerding attribution. The [vendoring notes](../shared/src/main/jni/speexdsp/README.md) identify the source version and build configuration.

## Software Opus microphone encoder

DiPlay vendors [Concentus](https://github.com/lostromb/concentus), Logan Stromberg's pure-Java port of the Opus reference library from Xiph.Org Foundation, Skype Limited, Microsoft Corporation and other contributors. It encodes the CarPlay microphone on head units whose Android has no MediaCodec Opus encoder.

Concentus uses the Opus BSD-style license. The full copyright notices, redistribution conditions and disclaimer are retained in [the vendored LICENSE file](../shared/src/main/java/org/concentus/LICENSE). The [vendoring notes](../shared/src/main/java/org/concentus/README.md) identify the source commit.

## Experimental authentication data

The public preview APK includes an accessory certificate/key pair recovered from public Carlinkit C2Air Allwinner V821 firmware during the owner's local investigation. These data are not newly generated Apple-issued credentials for DiPlay and are not relicensed as project source code. They are bundled in the preview APK to reproduce the offline experiment; continued acceptance and suitability for general distribution are unresolved. The source archive does not contain the private key, and the separate Android APK-signing key is never distributed.

## Download website

The static site layout, CSS and generator adapt DiAuto (AGPL-3.0). The AGPL license text is included with the source.

## BYD HUD maneuver icons

Required Notice: Copyright AndyShaman (https://github.com/AndyShaman/BYDMate)

The maneuver PNGs under `shared/src/main/assets/byd-hud-icons` were imported from BYDMate. Its PolyForm Noncommercial 1.0.0 terms and required notice are included alongside the assets. These files are separate from the project code license; upstream describes them as donor assets and their original provenance is not independently established. The validated DiLink5.1 windshield path uses factory turn codes rather than these images.

## CarPlay climate protocol

The FX11 climate integration adapts the UI Sync state machine, CAF climate schema and
APTransport duplex framing described by [LIVI commit 3f86ad7](https://github.com/f-io/LIVI/tree/3f86ad72049aa52d2969f083812ea37c80b65ea6/native/crates/livi-cp/src),
GPL-3.0-or-later. Sources: `ui_sync.rs`, `vehicle_state.rs`,
`vehicle_state/climate.rs`, `info.rs` (physical display panels, also cross-checked against e7b20b8), and `iap_tunnel.rs`. Independent synthetic byte fixtures
were generated with that revision's OPACK/bplist codecs and UI Sync implementation;
see `shared/src/test/resources/livi-ultra/README.md`. The Kotlin adapter uses ECARX feedback instead of simulated values.

The v68 connection work additionally references [LIVI e7b20b8](https://github.com/f-io/LIVI/tree/e7b20b818f052958afc95dcf91acc25c70643c02/native):
`livi-runtime/src/{ident,bringup}.rs`, `livi-cp/src/stack/data_streams.rs`,
`livi-cp/src/{info,ui_sync}.rs`, `vehicle_state/{climate,resources}.rs`, and
`livi-core/src/theme_catalog.rs`. It adds lane replacement, instrument plugin panels,
and opt-in layout requests. Theme identifier/version/SDK and panel/layout metadata
were checked against the Apple CarDDPAssets catalog and package on 2026-10-31.
No Apple CoreAnimation bundles or artwork are included in the source or APK.
Actual iPhone acceptance/rendering remains a device compatibility test.

The v71 vehicle page also adapts `vehicle_state/settings.rs` from LIVI e7b20b8
(Copyright 2025–2026 Lasse Heitgres, GPL-3.0-or-later, additional terms in LIVI NOTICE).
It uses info-line and section types with FX11 feedback and localized labels; it does not
copy LIVI simulator controls or DAB radio capabilities into the FX11 profile.
