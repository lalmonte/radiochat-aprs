# RadioChat APRS

**A free, open-source APRS client for Android that turns a [Radtel RT-950 Pro](#1-ble-kiss-radtel-rt-950-pro)
into a full APRS station over Bluetooth — no TNC cable, no extra hardware.**

It connects to the RT-950 Pro over **BLE KISS**, to **DireWolf** (or any KISS TNC) over
**TCP**, and to the global **APRS-IS** network — all three at the same time if you want.
Chat with ACKs, a live map, a packet monitor, a GPS beacon and an iGate, in one app.

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
| **Radtel RT-950 Pro** | Bluetooth LE, KISS BLE mode (APRS menu on the radio) | Supported — RX confirmed; phone-initiated TX depends on your firmware |
| **DireWolf** (PC/Raspberry Pi) | KISS over TCP (`KISSPORT`) | Supported |
| **Any KISS TNC** reachable over TCP | KISS over TCP | Should work — reports welcome |
| No radio at all | APRS-IS over the internet | Supported |

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

### 1. BLE KISS (Radtel RT-950 Pro)

`BleUartManager` + `BleUartProfile`. Radio menu: APRS → **KISS(BLE)** with TX and RX enabled. Disconnect other BLE apps (for example CPS programmers) so the radio advertises.

| Role | UUID |
|------|------|
| Service | `0000FFE0-0000-1000-8000-00805F9B34FB` |
| Notify (radio → app) | `0000FFE1-…` |
| Write (app → radio) | `0000FF31-…` (fallback `FFE2` on some firmware) |

Community reference: [mecta02/aprs](https://github.com/mecta02/aprs).

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

TX path preference: **verified APRS-IS > KISS TCP**. BLE is used for RF receive; phone TX over the radio’s KISS BLE is firmware-dependent.

---

## Features in depth

### Chat and ACK

- Incoming chat is **exact callsign + SSID**. `HI3LAG-7` ≠ `HI3LAG-3` ≠ `HI3LAG`. Bulletins `BLN*` are still accepted.
- ACK is the exception: addressed to this SSID, **or** to the base callsign without SSID (`:HI3LAG :ack01`).
- Status: `NONE` / `SENT` (one check) / `DELIVERED` (two checks) / `REJECTED`.
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
│   ├── ble/                  BLE scan, GATT, KISS write
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
