# RadioChat APRS

**A free, open-source APRS client for Android that turns a
[Radtel RT-950 Pro](#radtel-rt-950-pro--plain-kiss) into a full APRS station over
Bluetooth — no TNC cable, no extra hardware.**

It connects to the RT-950 Pro over **BLE KISS**, to **DireWolf** (or any KISS TNC) over
**TCP**, and to the global **APRS-IS** network — all three at the same time if you want.
Support for the [BTECH UV-PRO](#btech-uv-pro--benshi-protocol-experimental) is in, and
looking for testers. Chat with ACKs, a live map, a packet monitor, a GPS beacon and an
iGate, in one app.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#build-and-run)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)](#architecture)
[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/luisalmonte)

Application id: `com.aprs.radiochat` · Version `1.0.0`  
minSdk **31** (Android 12+) · targetSdk / compileSdk **35** · Java / Kotlin **17**

Author: Luis Almonte ([HI3LAG](https://www.qrz.com/db/HI3LAG))  
License: **GPL-3.0-or-later** — see [License](#license)

---

## Radio support

| Hardware | How it connects | Status |
|----------|-----------------|--------|
| **Radtel RT-950 Pro** | Bluetooth LE, KISS BLE mode (APRS menu on the radio) | Receive only — its firmware does not key PTT from phone KISS |
| **BTECH UV-PRO** | Bluetooth LE, Benshi protocol | Receive **and transmit** — RX confirmed on hardware, TX in testing |
| **DireWolf** (PC/Raspberry Pi) | KISS over TCP (`KISSPORT`) | Supported |
| **Any KISS TNC** reachable over TCP | KISS over TCP | Should work — reports welcome |
| No radio at all | APRS-IS over the internet | Supported |

Pick your radio in **More → Links & network → Bluetooth** before scanning. The two
handhelds do not share a protocol, so the app needs to know which one it is talking to.

The radio is **optional**: with just APRS-IS you already get chat, the map and the logs.

---

## Install

RadioChat APRS is **free software and free of charge**. There is no paid tier, no ads and
no tracking.

**Download the APK.** Grab the latest build from the
[Releases](../../releases) page and install it on your phone. You will need to allow
installing from an unknown source the first time.

**Or build it from source.** Everything needed to compile the app is in this repository —
see [Build and run](#build-and-run).

### Support the project

The app is free and will stay free. If it is useful to you on the air, a coffee helps pay
for the upkeep — testing hardware, and the time that goes into maintaining it.

<a href="https://buymeacoffee.com/luisalmonte" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" height="41" width="174"></a>

Donations are entirely voluntary. They buy no features, no priority and no influence over
the project — every user gets exactly the same app. Reporting a good bug, or telling me
which radios work, is worth just as much.

73 de HI3LAG.

---

## What it does

RadioChat APRS is a handheld-oriented APRS station:

- **Chat** — APRS text messages (`:CALLSIGN :text{id`) with WhatsApp-style ACK ticks
- **Map** — live stations on OpenStreetMap (osmdroid), APRS icons, own beacon track
- **Logs** — live packet monitor (RF + Internet) with callsign symbols
- **Config** — callsign, passcode, BLE, DireWolf KISS TCP, APRS-IS, iGate, GPS beacon

It is designed so **SSID matters**: `HI3LAG-7` (this phone) is not `HI3LAG-3` (DireWolf on a PC). Chat, map, ACKs, and “own position” all compare **callsign + SSID**.

A valid amateur radio license and legal APRS frequencies / internet use in your country are required.

---

## Screens

Bottom navigation (no cross-fade between tabs, to keep the map from blocking the UI):

| Tab | Screen | Role |
|-----|--------|------|
| Config | `ConnectionScreen` | Callsign, passcode, BLE scan, KISS TCP host/port, APRS-IS server, receive radius (km), iGate, GPS beacon |
| Chat | `ChatScreen` | Conversation list (swipe to delete) and thread with bubbles, source badges (BLE / TCP / IS), ACK icons |
| Map | `MapScreen` | OSM tiles, station markers with Hessu APRS symbols, own position, polyline of **sent** beacons only |
| Logs | `LogsScreen` | Cards for each packet: APRS icon, callsign, route, kind/transport chips, optional RAW. Follows the newest packet when scrolled to the top |

Inside a chat thread, the list jumps to the latest message when you send or when the peer writes.

---

## Transports

Three independent paths can run at the same time.

```
                    ┌─────────────┐
   Radtel RT-950 ──►│ BLE KISS    │──┐
                    └─────────────┘  │
                    ┌─────────────┐  │    KissFrameHub
   DireWolf TCP ───►│ KISS TCP    │──┼──► Chat / Map / Logs / iGate
     (KISSPORT)     └─────────────┘  │
                    ┌─────────────┐  │
   rotate.aprs2.net►│ APRS-IS     │──┘
     (port 14580)   └─────────────┘
```

### 1. BLE (handheld radio)

`BleUartManager` owns the GATT state machine — scan, connect, MTU, notifications, write
queue — and knows nothing about any particular radio. Everything model specific lives in a
[`BleRadioProfile`](app/src/main/java/com/aprs/radiochat/data/ble/BleRadioProfile.kt):
which attributes to look for, how to recognise the device while scanning, and how AX.25
payloads are framed. Adding a radio is a new profile, never a change to the manager.

#### Radtel RT-950 Pro — plain KISS

Radio menu: APRS → **KISS(BLE)** with TX and RX enabled. Disconnect other BLE apps (for
example CPS programmers) so the radio advertises.

| Role | UUID |
|------|------|
| Service | `0000FFE0-0000-1000-8000-00805F9B34FB` |
| Notify (radio → app) | `0000FFE1-…` |
| Write (app → radio) | `0000FF31-…` (fallback `FFE2`, `FFE1` on some firmware) |

Community reference: [mecta02/aprs](https://github.com/mecta02/aprs).

#### BTECH UV-PRO — Benshi protocol (experimental)

The UV-PRO and its siblings (Vero VR-N76, RadioOddity GA-5WB) do **not** speak KISS over
BLE. They expose a vendor service and a framed message protocol in which AX.25 frames
travel as fragmented "TNC data" messages. Two differences matter at the transport level:
the RX characteristic is an **indication** rather than a notification, and messages are
atomic, so one is never split across GATT writes.

| Role | UUID |
|------|------|
| Service | `00001100-d102-11e1-9b23-00025b00a5a5` |
| Indicate (radio → app) | `00001102-d102-11e1-9b23-00025b00a5a5` |
| Write (app → radio) | `00001101-d102-11e1-9b23-00025b00a5a5` |

Protocol reference: [khusmann/benlink](https://github.com/khusmann/benlink), which
reverse engineered these radios.

The radio stays **silent until the app registers for events**. Registering
`HT_STATUS_CHANGED` is what also switches on `DATA_RXD`, the event that carries received
frames — a quirk documented by benlink. The app sends that registration as soon as the
link is up; without it you get a healthy-looking connection and no packets at all.

| Field | Value |
|-------|-------|
| Command group | `BASIC` = 2 |
| Register for events | `REGISTER_NOTIFICATION` = 6, event `HT_STATUS_CHANGED` = 1 |
| Received data | `EVENT_NOTIFICATION` = 9, event `DATA_RXD` = 2 |
| Send data | `HT_SEND_DATA` = 31 |

In a TNC data fragment the channel id is a **trailing** byte, not a header, which is easy
to get backwards.

> **Status:** UUIDs, command identifiers and framing all come from the benlink source.
> Confirmed to connect on a real UV-PRO; **end-to-end packet flow is still being
> verified**. Reports from UV-PRO owners are very welcome — please open an issue.

BLE is **optional** (`bluetooth_le` is not required). The app works with DireWolf and/or APRS-IS alone.

### 2. KISS TCP (DireWolf)

`TcpKissTncClient` speaks binary KISS (`FEND … FEND`) to DireWolf `KISSPORT` (default `192.168.1.10:8001`). Typical `direwolf.conf`:

```
KISSPORT 8001
```

Incoming AX.25 payloads are dispatched with a **synchronous callback** so the log does not drop frames if a SharedFlow buffer is full.

### 3. APRS-IS

`AprsIsClient` logs in as `user CALL pass N vers RadioChatAPRS 1.0`, then sends `# filter …`. Default server: `rotate.aprs2.net:14580`. Unverified passcode stays RX-only.

Filter (`AprsIsFilter`) is javAPRSSrvr **OR** elements — never a lone `t/m` (that would pull every message on Earth):

| Token | Meaning |
|-------|---------|
| `r/lat/lon/km` | Range around last known own position |
| `m/km` | Range around this station’s last TX position |
| `g/BASE/FULL` | Messages addressed to the base callsign and to the SSID in use |
| `b/BASE` | Echo of own packets |
| `p/PRE` | Regional prefix (e.g. `HI3LAG` → `p/HI3`) |

The **receive radius** in Config is applied by the **IS server** and on the **map**. The **log records every packet** that actually arrives on the socket or TNC.

---

## Packet pipeline

**Incoming RF (BLE or TCP)**

```
KISS bytes
  → KissCodec.Decoder (FEND/FESC, data cmd only)
  → Ax25Frame.parse (optional FCS strip)
  → Tnc2Codec.effective (unwrap third-party `}OTA>…`)
  → AprsPacketParser
        TextMessage → Chat (if addressed to this SSID) + Logs
        Position    → StationRepository / map + Logs
        Other/RAW   → Logs
```

**Incoming APRS-IS**

```
TNC2 line  SOURCE>DEST,path:info
  → Tnc2Codec.parse + unwrap `}`
  → messages / uncompressed position / Mic-E / other
```

**Outgoing chat**

```
UI Send
  → AprsMessageCodec.build (9-char padded addressee, `{msgid`)
  → APRS-IS if verified, else KISS TCP (DireWolf)
  → ACK later sets DELIVERED / REJECTED
```

TX path preference: **verified APRS-IS > KISS TCP > BLE**.

Transmitting over Bluetooth depends on the radio, and the app will not pretend otherwise:
the **UV-PRO transmits** frames handed to it over BLE, while the **RT-950 Pro accepts the
write and never keys up**, so it is marked receive-only and transmissions are routed
elsewhere rather than reporting a message as sent that never reached the air.

ACKs are the exception to the ordering: they go back over **the same link the message
arrived on** whenever that link can transmit. A station that called you on RF may not be
on APRS-IS at all, and an ACK sent to the internet would never reach them.

---

## Features in depth

### Chat and ACK

- Incoming chat is **exact callsign + SSID**. `HI3LAG-7` ≠ `HI3LAG-3` ≠ `HI3LAG`. Bulletins `BLN*` are still accepted.
- ACK is the exception: addressed to this SSID, **or** to the base callsign without SSID (`:HI3LAG :ack01`).
- Status: `NONE` / `SENT` (one check) / `DELIVERED` (two checks) / `REJECTED`.
- **Resending:** long-press any outgoing message the far end has not confirmed to get
  *Send again*. The APRS message id is reused on purpose — an ACK then lands on the same
  bubble instead of creating a second one, and a station that did receive the original
  recognises the retry as a duplicate and simply re-ACKs it. A retried message shows `↻n`
  next to its timestamp. The path is resolved again on each try, so a message that failed
  over one link goes out over whatever is up now.
- History is persisted (`ChatStore`). Opening a thread marks messages read (unread badge on the list).
- Own RF echo is ignored unless the addressee is this station.

### Map and beacons

- Stations keyed by **canonical callsign including SSID**.
- Own map position from phone GPS and/or a radio beacon that matches **this** SSID. A stored position from another SSID is discarded (unless it came from phone GPS).
- **Beacon route** = points actually transmitted (`BeaconTrackStore` CSV), not every GPS sample.
- `BeaconService` sends uncompressed APRS positions on the configured interval via IS and/or KISS TCP.
- `AprsTrackingService` is a **location foreground service**. It is started from `MainActivity.onStart` when the beacon is on — **never** from `Application.onCreate` (Android 12+ FGS restriction).
- Optional battery-optimization exemption. GPS is throttled (about 5 s / 15 m) so the UI does not freeze.

### iGate (RF → IS)

`IGateService` forwards KISS frames to APRS-IS when the session is **verified** and iGate is enabled. It skips TCPIP/TCPXX paths, third-party `}` wrappers, duplicates, and rate-limits (~1.2 s) so the server does not drop the client.

### Live logs

- Separate RF and IS rings (RF is not evicted by a busy IS feed).
- APRS symbol per callsign (cached from the last position packet; Hessu 64 px sheets in `assets/aprs/`).
- UI snapshot ~2 s; no per-packet Main-thread reverse/filter (avoids ANR).
- Auto-scroll only while the list is pinned to the top.

---

## Architecture

MVVM + a small manual DI graph in `RadioChatApp` (**no Hilt**). UI observes `StateFlow` via ViewModels.

```
com.aprs.radiochat
├── RadioChatApp.kt          Application + DI
├── MainActivity.kt           Permissions, FGS start
├── data/
│   ├── ble/                  GATT state machine + per-radio profiles
│   ├── kiss/                 KissCodec, KissFrameHub
│   ├── tnc/                  TcpKissTncClient
│   ├── aprs/                 AX.25, messages, position, Mic-E, symbols, geo
│   ├── aprsis/               Client, TNC2, filter, passcode, iGate
│   ├── beacon/               Periodic GPS beacon
│   ├── location/             Phone GPS, track store, battery exemption
│   ├── service/              AprsTrackingService (FGS)
│   ├── repository/           Chat, stations, logs, settings
│   └── model/                Packets, chat, map, connection states
└── ui/
    ├── connection/  chat/  map/  logs/
    ├── navigation/           Bottom bar (Config | Chat | Map | Logs)
    ├── common/               AprsSymbolImage
    └── theme/
```

### Important types

```kotlin
sealed class AprsPacket {
    data class TextMessage(...)
    data class Position(...)      // lat/lon, symbol table+overlay, comment
    data class Other(...)
}

enum class MessageAckStatus { NONE, SENT, DELIVERED, REJECTED }
enum class LogTransport { RF_BLE, TNC_TCP, INTERNET }
enum class LogKind { MESSAGE, POSITION, OTHER, RAW }
```

---

## APRS codecs

| Component | Role |
|-----------|------|
| `KissCodec` | TAPR KISS: FEND `0xC0`, FESC `0xDB` |
| `Ax25Frame` | UI frames, address encode/decode, optional FCS |
| `AprsMessageCodec` | `:ADDRESSEE:text{id`, ACK/REJ, padded and DireWolf-unpadded addressees |
| `AprsPositionParser` | Uncompressed `!` / `=` / `/` / `@`, basic base91 compressed |
| `MiceParser` | Mic-E (lat in dest, rest in info) |
| `Tnc2Codec` | `SRC>DST,path:info`, third-party unwrap `}` |
| `AprsSymbolIcons` | Crop from `aprs-symbols-64-{0,1,2}.png` |

Outgoing messages use destination `APRS`. DireWolf TX typically uses digis `WIDE1-1,WIDE2-1`; BLE often lets the radio add the path.

---

## Permissions

| Permission | Why |
|------------|-----|
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | Radio discovery and GATT (Android 12+) |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Map + GPS beacon |
| `FOREGROUND_SERVICE` / `_LOCATION` | Tracking with screen off |
| `POST_NOTIFICATIONS` | Tracking notification |
| `WAKE_LOCK` | Keep GPS/beacon alive |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Optional exemption |
| `INTERNET` / `ACCESS_NETWORK_STATE` | APRS-IS + OSM tiles |

Runtime prompts: `RequestBlePermissions`. Cleartext TCP is allowed (local DireWolf). OSM tiles use `User-Agent` = application id ([tile usage policy](https://operations.osmfoundation.org/policies/tiles/)).

---

## Settings (persisted)

SharedPreferences `radiochat_aprs`: callsign, APRS-IS passcode (auto from `AprsIsPasscode` or custom), server, range km (default 100), iGate, TCP host/port, beacon on/interval/comment/symbol, last own position.

---

## Build and run

Open the folder in Android Studio (Ladybug / Koala or newer) and sync Gradle.

```bash
./gradlew :app:assembleDebug
./gradlew :app:test
```

### Typical setup

1. Set **your callsign with SSID** (example: `HI3LAG-7`).
2. **APRS-IS:** passcode, Connect. Optional: enable iGate after verified login.
3. **DireWolf:** same LAN, `KISSPORT 8001`, connect KISS TCP. Keep the PC’s SSID different (example: `HI3LAG-3`).
4. **Radio:** KISS BLE mode, scan and connect the RT-950 Pro.
5. Optional: enable **GPS beacon** (notification + background tracking).

Unit tests (JUnit 4) live under `app/src/test`:

- `AprsCodecTest`, `AprsIsCodecTest`, `AprsIsFilterTest`
- `KissCodecTest`
- `ChatAckTest`, `ChatConversationTest`
- `TrackPointTest`

---

## Releases

Releases are built automatically by
[`.github/workflows/release.yml`](.github/workflows/release.yml). Pushing a semver tag
runs the unit tests, builds a **signed** release APK, and publishes a GitHub Release with
the APK and its SHA-256 checksum attached:

```bash
git tag -a v1.0.1 -m "RadioChat APRS 1.0.1"
git push origin v1.0.1
```

`versionName` comes from the tag and `versionCode` is derived from it
(`1.0.1` → `10001`), so the two can never drift apart. Release notes are taken from the
matching `## [1.0.1]` section of [CHANGELOG.md](CHANGELOG.md) when there is one. A tag
with a suffix (`v1.1.0-beta1`) is published as a pre-release.

### One-time signing setup (maintainer)

Android identifies an app by its **signing key**, not by its version. An update signed
with a different key is rejected by the device: users would have to uninstall and lose
their chat history and settings. So the key below is created **once** and kept for the
life of the app.

```bash
keytool -genkeypair -v -keystore release.jks -alias radiochat \
  -keyalg RSA -keysize 4096 -validity 10000
```

> **Back up `release.jks` and its passwords somewhere you cannot lose them** — a password
> manager and an offline copy. If the key is lost, you cannot ship an update to anyone who
> already installed the app. If it leaks, someone else can sign builds that phones will
> accept as yours. It is deliberately **not** in this repository; `.gitignore` blocks
> `*.jks` and `*.keystore`.

Then add four repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|--------|-------|
| `SIGNING_KEYSTORE_BASE64` | `base64 -i release.jks \| pbcopy` (macOS) or `base64 -w0 release.jks` (Linux) |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | `radiochat` |
| `SIGNING_KEY_PASSWORD` | Key password |

The workflow fails with a clear message if any of the four is missing, rather than
publishing an APK nobody can install. Local builds are unaffected: with no signing
environment present, `assembleRelease` produces an unsigned APK exactly as before.

---

## Performance notes

The UI used to ANR when every IS/KISS packet reversed large lists and rebuilt map markers on the main thread. Current safeguards:

- Log and station maps publish **snapshots** (~1.5–2 s), not per packet
- Log filter/sort on `Dispatchers.Default`
- Map: incremental markers, cap on stations / track points, deferred `MapView` inflate, symbol sheets preloaded on IO
- Tab switch: no enter animation / no `saveState` (avoids Map + Logs composed together)
- GPS and IS filter updates are throttled

---

## Dependencies (app)

- Jetpack Compose BOM `2024.12.01`, Material 3, Navigation 2.8
- Lifecycle / ViewModel Compose
- osmdroid-android `6.1.20` (no map API key)
- kotlinx-coroutines-android `1.9.0`

---

## Operating notes

Operate only where you are licensed. A valid amateur radio licence and legal APRS
frequencies / internet use in your country are required. Do not flood APRS-IS (`t/m` is
intentionally omitted). Respect the OSM tile usage policy.

---

## License

Copyright (C) 2026 Luis Almonte (HI3LAG).

RadioChat APRS is free software: you can redistribute it and/or modify it under the terms
of the **GNU General Public License, version 3 or (at your option) any later version**, as
published by the Free Software Foundation.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the [LICENSE](LICENSE) file, or <https://www.gnu.org/licenses/gpl-3.0.html>, for the
full text.

In short: you may use, study, modify and redistribute this app, including commercially,
as long as anything you distribute stays under the GPL and ships its source.

### Name and logo

The **GPL covers the source code, not the branding**. The name *RadioChat APRS* and the
application icon are **not** covered by the licence above and remain the author's.

If you distribute a modified version, please give it a different name and icon. This is
the same convention DAVx⁵ and Tasks.org use: it keeps users from mistaking your build for
this one, and keeps any bug reports pointed at whoever actually shipped the build. You
keep every freedom the GPL grants you — this is about identity, not permission.

### Third-party components

| Component | License |
|-----------|---------|
| [osmdroid](https://github.com/osmdroid/osmdroid) | Apache-2.0 |
| AndroidX / Jetpack Compose | Apache-2.0 |
| kotlinx-coroutines | Apache-2.0 |
| [hessu/aprs-symbols](https://github.com/hessu/aprs-symbols) | Mixed — see below |

Map data is © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors,
available under the [ODbL](https://opendatacommons.org/licenses/odbl/).

The APRS symbol sheets in `app/src/main/assets/aprs/` come from hessu/aprs-symbols
(aprs.fi / OH7LZB). That collection is **not under a single licence**: it mixes public
domain artwork, CC BY-SA 2.0 designs, symbols of unknown provenance vectorised from the
original APRS set, and a handful of manufacturer logos whose trademarks belong to their
owners. See `COPYRIGHT.md` in that repository for the per-symbol detail.

---

## Contributing

Bug reports, patches and translations are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).
Contributions are accepted under the GPL-3.0-or-later terms above.
