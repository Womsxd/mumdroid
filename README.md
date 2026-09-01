# mumdroid

**mumdroid** is an open-source [Mumble](https://www.mumble.info/) voice-chat client for Android, written from scratch in **Kotlin** with **Jetpack Compose**.

[简体中文](README.zh-CN.md)

---

## Table of contents

- [Highlights](#highlights)
- [Features](#features)
- [Architecture](#architecture)
- [Protocol & Audio](#protocol--audio)
- [Security model](#security-model)
- [Settings reference](#settings-reference)
- [Project layout](#project-layout)
- [Requirements](#requirements)
- [Build from source](#build-from-source)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)
- [Third-party components](#third-party-components)
- [References](#references)
- [Special Thanks](#special-thanks)
- [License](#license)

---

## Highlights

- **Customizable Android audio routing.** Headset / Bluetooth / loudspeaker / earpiece with a user-ranked priority list and a communication-vs-media playback path.
- **Server administration.** Channels, access tokens, registered users, bans (timed or permanent, by certificate or IP), kick, move, mute, deafen, priority speaker. Channel access is password-based; a full ACL editor is not included.

---

## Features

### Voice

| Area | Options |
| --- | --- |
| Transmission | Continuous, voice activity (VAD), push-to-talk |
| VAD | Amplitude or signal-to-noise, dual threshold (0–100) with voice hold |
| Codec | Opus 48 kHz mono, 8–192 kbit/s, 1/2/4/6 frames per packet, restricted low delay |
| Noise suppression | Off / System / Speex / RNNoise / Speex + RNNoise, 0–60 dB |
| Gain | Microphone volume, System or Speex AGC with adjustable max gain |
| Echo cancellation | Off / System / Speex (software, speaker reference) |
| Output | Headset, Bluetooth, loudspeaker, earpiece — user-ranked order; communication or media path |
| Extras | Half duplex, incoming volume, soft limiter, jitter buffer with packet-loss concealment |
| Transport | UDP with automatic TCP fallback, forced TCP mode, QoS (DSCP) tagging |

### Connectivity

- **TLS-encrypted TCP control channel** with optional client certificate; reports TLS version, cipher suite and whether forward secrecy is offered.
- **Self-signed server certificate handling** with SHA-256 fingerprint capture; certificate pinning is enabled by default — a fingerprint mismatch pauses the connection so you can update the pin, trust once, or reject.
- **Server list** with plaintext UDP probing (legacy 12-byte *and* protobuf ping) reporting latency, user count, max users, bandwidth and version; optional periodic re-ping (5–60 s, off by default).
- **Automatic reconnect** with backoff, last-server / last-channel restore.
- **Statistics**: TCP/UDP ping (average, deviation, good/late/lost/resync) and UDP packet counters.

### Channels, users & admin

- Collapsible **channel / user tree** with join, link/unlink, listen/unlisten, per-channel user counts and talking states.
- Create, edit, remove and reposition channels — including temporary channels, max-user limits, descriptions and passwords (remembered as access tokens per address). Channel access control is limited to passwords, which are translated into server ACL rules; fine-grained ACL editing (groups, per-user grants) is not implemented.
- User context actions: information (versions, address, certificate, Opus support, ping statistics), mute, deafen, move, kick, ban, register, rename, unregister, priority speaker, ignore messages, local block.
- **Registered-user list** and **ban list** with search; timed or permanent bans by certificate or IP.
- **Text chat** to channels and users with history, system notices (joins, leaves, moves, kicks, bans) and notifications with inline reply.

### Interface

- Modern **Material 3** UI built with **Jetpack Compose**.
- **Bilingual**: English and Simplified Chinese, in-app language switcher.
- Light / dark / system themes, earpiece-proximity handling, keep-screen-awake while connected.
- Per-ABI APK splits (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`).

---

## Architecture

The codebase is organised into self-contained layers:

```
dev.woms.mumdroid
├── core/                   Framework-free layers
│   ├── proto/              Protobuf (lite) generated Mumble messages + codecs
│   ├── crypto/             OCB2-AES128 (CryptOCB2) and CryptState key material
│   ├── net/                MumbleClient (TCP/TLS), UdpVoiceManager, ping probes
│   ├── audio/              Opus codec, capture, playback, jitter buffer, DSP JNI
│   ├── i18n/               Runtime locale switching
│   └── model/              Channel / User / Server / settings data classes
├── data/                   Room database, DAOs, certificate stores, settings (DataStore)
├── service/                MumbleService + focused session collaborators
└── ui/                     Compose screens, dialogs, settings, theme
```

---

## Protocol & Audio

### Protocol

- **Control channel.** TCP/TLS, 6-byte big-endian header (`type`, `length`) followed by a protobuf message. Message type numbers mirror `MUMBLE_ALL_TCP_MESSAGES` from the official `MumbleProtocol.h` (type 26, plugin data, is ignored); the advertised client version is 1.5.0.
- **Voice channel.** One UDP datagram per packet, encrypted as a whole with OCB2-AES128 (`[4-byte overhead][encrypted header + payload]`).
    - Servers ≥ 1.5.0: protobuf framing (`MumbleUDP.Audio` / `MumbleUDP.Ping`).
    - Older servers: legacy framing, `(type << 5) | target`, type 4 = Opus.
    - Only Opus is decoded; CELT and Speex payloads are dropped, matching the modern desktop client.
- **Connectivity.** Periodic UDP pings measure round-trip time and detect a broken voice path. If UDP stops working in either direction the client switches to TCP tunnelling, and switches back once UDP recovers. Persistent decryption failures trigger a crypt resync.
- **Bandwidth adaptation.** The requested bitrate and packet size are reduced (`adjustBandwidth`) until IP + UDP + OCB2 + framing overhead fits the server's `max_bandwidth`, down to a floor of 8 kbit/s.

### Audio pipeline

```
AudioRecord ─► MicCaptureEngine ─► SoftLimiter ─► OpusCodec.encode ─► UDP/TCP
                     │
                     ├─ AudioPreprocessor (Speex / RNNoise / system)
                     ├─ AGC (system effect or Speex)
                     └─ AEC (system effect or Speex against speaker reference)

UDP/TCP ─► UdpVoiceManager ─► OpusCodec.decode ─► VoiceJitterBuffer ─► AudioTrack
                                                  (reorder, PLC, fades)
```

- Frames are 10 ms (480 samples); 1, 2, 4 or 6 frames are bundled per packet; 16-bit mono PCM at 48 kHz throughout; a per-session decoder map supports up to 32 simultaneous speakers with idle reaping.
- The jitter buffer works on the official `frameNumber` time axis, reorders packets, drops late ones, prerolls incoming talk spurts and conceals gaps at playback time.
- The pure-Java [Concentus](https://github.com/lostromb/concentus) Opus codec is used via a Maven dependency; RNNoise and speexdsp are built natively from vendored submodules via CMake and exposed through thin JNI bindings — no prebuilt binaries, everything builds from source.
- Output routing tracks device insertion/removal, starts/stops Bluetooth SCO, and applies the selected communication or media usage. The earpiece uses the proximity sensor for screen blanking while connected.

---

## Security model

| Concern | Approach |
| --- | --- |
| Server identity | SHA-256 certificate fingerprint pinned on first connect; mismatch raises a user prompt (update / trust once / reject) |
| Client identity | One active PKCS#12 user certificate presented during the TLS handshake; generate locally (Bouncy Castle) or import `.p12`/`.pfx` |
| Key storage | Each certificate lives in its own PKCS#12 keystore file in app-private storage; crypt key material is erased on disconnect |
| Voice privacy | OCB2-AES128 on every voice datagram, including tunneled-over-TCP packets |
| Channel access | Channel passwords are converted into server ACL rules (deny-all + grant `#password`); fine-grained ACL editing is not exposed in the UI |
| Minimisation | No telemetry, no analytics, no accounts — the app only talks to the servers you add |

---

## Settings reference

Settings live in DataStore and are grouped into five screens (plus About).

**Audio** — noise suppression engine and level, microphone source
(microphone or voice communication), AGC backend and max gain, echo
cancellation backend, microphone volume, transmit quality, audio per packet,
low latency mode, incoming volume, playback path, default output order, half
duplex.

**Network** — voice transport (UDP or forced TCP), QoS tagging (DSCP EF on the
voice socket), automatic reconnect, certificate pinning, server-list auto ping
and interval.

**Identity & certificates** — default username, active user certificate,
generation / import / export, recorded server certificates.

**Appearance** — theme (system / light / dark), language (system / English /
Simplified Chinese), channel user counts.

**General** — keep the screen awake while connected, chat notifications.

**About** — version, build hash, open-source licenses.

---

## Project layout

```
.
├── app/
│   ├── schemas/                 Exported Room schemas (v1 … v5)
│   └── src/
│       ├── main/
│       │   ├── cpp/             CMake + JNI bindings for speexdsp and RNNoise
│       │   ├── proto/           Mumble.proto, MumbleUDP.proto
│       │   ├── java/…/          Kotlin sources (see Architecture)
│       │   └── res/             values/ (English), values-zh/ (中文)
│       └── test/                JVM unit tests
├── gradle/libs.versions.toml    Single version catalog
└── settings.gradle.kts
```

Persistence uses Room (database version 5, migrations 1→2→3→4→5 preserved) for
servers, certificates and access tokens, and DataStore for settings.

---

## Requirements

| Item | Requirement |
| --- | --- |
| Android | 8.0 (API 26) or newer |
| ABIs | `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` |
| Permissions | Internet, record audio, modify audio settings, foreground service (microphone), notifications, wake lock |
| Server | Any Mumble server (Murmur) speaking Opus — 1.5.0+ gets protobuf UDP framing, older servers use legacy framing |

---

## Build from source

The DSP libraries are git submodules, so clone with `--recursive` (or init them afterwards — the CMake build fails with an explicit message if they are missing).

```bash
git clone --recursive https://cnb.cool/womsxd/mumdroid.git
cd mumdroid

# if you already cloned without --recursive
git submodule update --init --recursive

./gradlew :app:assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

Other useful tasks:

```bash
./gradlew :app:assembleRelease   # R8-optimised, per-ABI APKs
./gradlew :app:test              # JVM unit tests
./gradlew :app:lint              # static analysis
```

> **Note**
> The RNNoise model weights are not stored in the upstream repository. CMake downloads the tarball matching `rnnoise/model_version` at configure time and verifies its SHA-256, so the first build needs network access.

Toolchain: Android Gradle Plugin 9.3, Kotlin 2.4, Compose BOM 2026.02.01, NDK 30 with CMake 3.22, Java 11 source/target compatibility. Release builds enable R8 shrinking.

---

## Testing

```bash
./gradlew :app:test
```

The JVM test suite covers the pieces that are easy to get subtly wrong: the UDP/TCP codecs, `PacketDataStream` varint edge cases, OCB2 framing, ping encoding/decoding, jitter-buffer behaviour, VAD thresholds, output routing rules, channel-tree maintenance, ACL permission checks, channel-password handling, bans, and settings migration.

---

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Build fails in CMake about a missing `rnnoise` / `speexdsp` directory | Run `git submodule update --init --recursive` |
| First build fails downloading RNNoise weights | CMake fetches and verifies the model tarball — allow network access at configure time |
| Server connects but nobody can hear you | Microphone permission, mute/deafen state, transmission mode, and whether UDP fell back to TCP |
| Voice is choppy | Raise "audio per packet", lower transmit quality, or switch to a stronger Wi-Fi/cellular signal |
| Certificate prompt on a known server | The server certificate changed. Compare the fingerprints, then update the pin, trust once, or reject |
| Server rejects the connection with "not using Opus" | mumdroid only speaks Opus; the server must have Opus selected |
| Nothing plays through Bluetooth | Check the output order, and whether the playback path is communication (SCO) or media (A2DP) |

---

## Third-party components

| Component | License | Use |
| --- | --- | --- |
| [Mumble](https://www.mumble.info/) | BSD-3-Clause | Protocol and client behaviour reference |
| [Concentus](https://github.com/lostromb/concentus) | BSD-3-Clause | Pure-Java Opus encoder/decoder |
| [RNNoise](https://github.com/xiph/rnnoise) | BSD-3-Clause | Neural noise suppression (vendored submodule) |
| [speexdsp](https://github.com/xiph/speexdsp) | BSD-3-Clause | Pre-processor, AGC and echo cancellation (vendored submodule) |
| [Bouncy Castle](https://www.bouncycastle.org/) | MIT | Client certificate generation |
| Android Jetpack (Compose, Room, DataStore, Lifecycle) | Apache-2.0 | UI and persistence |
| Kotlin & kotlinx.coroutines | Apache-2.0 | Language and concurrency |
| Protocol Buffers (Lite) | BSD-3-Clause | Control and UDP message encoding |
| Material Design icons | Apache-2.0 | Iconography |

The full, localised attribution list is also shown in-app under **Settings → About → Open Source Licenses**.

---

## References

- Official client: <https://github.com/mumble-voip/mumble>
- Protocol library: <https://github.com/mumble-voip/libmumble>
- RNNoise: <https://github.com/xiph/rnnoise>
- speexdsp: <https://github.com/xiph/speexdsp>
- Concentus: <https://github.com/lostromb/concentus>

---

## Special Thanks

- Mumla (development reference and partial inspiration): <https://github.com/mumla/mumla>

---

## License

mumdroid is released under the **BSD 3-Clause** license. The vendored and third-party components above retain their own licenses — see the `app/src/main/assets/licenses` directory for the full license texts.
