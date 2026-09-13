# Privacy Policy — RadioChat APRS

**Last updated: 7 September 2026**

RadioChat APRS (`com.aprs.radiochat`) is an amateur radio APRS client developed by Luis
Almonte (HI3LAG). This policy explains what the app does with your data.

## The short version

The app has **no backend**. There is no RadioChat server, no account, no analytics, no
advertising and no tracking SDK. The developer never receives your data.

What the app does transmit, it transmits **because you asked it to** — that is what an
APRS station does — and it goes to the amateur radio network, not to us.

## What stays on your device

All of the following is stored only in the app's private storage on your phone, and is
deleted when you uninstall the app:

| Data | Where | Why |
|------|-------|-----|
| Your callsign, SSID and APRS-IS passcode | `SharedPreferences` | Station identity |
| Connection settings (server, DireWolf host/port, radius) | `SharedPreferences` | Your setup |
| Chat history | `files/chat_messages.json` | So conversations survive a restart |
| Beacon track | CSV in app storage | The track drawn on the map |
| Last known own position | `SharedPreferences` | Map centring and the APRS-IS filter |

You can erase chat history in the app (**Chat → delete conversation / delete all**) and the
beacon track from the map screen. Uninstalling removes everything.

## Location

The app requests location permission and can run a **foreground location service** so the
GPS beacon keeps working with the screen off.

Your location is used to:

1. Show your own station on the map.
2. Build the APRS-IS receive filter (`r/lat/lon/km`), so the server sends you nearby traffic.
3. **Transmit your position as an APRS beacon — but only while you switch the GPS beacon
   on.** With the beacon off, your position is never transmitted.

Location data is never sent to the developer and never sold or shared with third parties.

## What you transmit, and why it is public

APRS is, by design, a **public** amateur radio protocol. When you enable the beacon or send
a chat message, that packet — including your callsign and, for beacons, your coordinates —
is transmitted over the radio and/or to the APRS-IS network. Once there it may be received
by any station, stored and republished indefinitely by public services such as aprs.fi, and
it is outside the developer's control.

This is normal, expected APRS behaviour, not a defect. **Do not enable the beacon if you do
not want your position to be public.**

If you turn on the **iGate** feature, packets your radio receives over RF are forwarded to
APRS-IS under your callsign. That is you operating as a gateway.

## Network connections

The app connects only to:

| Destination | Purpose |
|-------------|---------|
| The APRS-IS server you configure (default `rotate.aprs2.net:14580`) | Sending and receiving APRS traffic |
| A DireWolf / KISS TNC host you configure, on your own network | RF traffic over KISS TCP |
| OpenStreetMap tile servers | Map tiles |

Your APRS-IS passcode is sent to the APRS-IS server as part of the standard login. It is not
a secret — it is a value derived from your callsign, documented publicly by the APRS-IS
project — and it is not sent anywhere else.

## Bluetooth

Bluetooth permissions are used solely to find and talk to your radio — a BTECH UV-PRO or a
Radtel RT-950 Pro — over BLE. This carries APRS traffic between the app and the radio, and
with the UV-PRO it is also how the app hands the radio frames to transmit. No Bluetooth
data leaves your device, and none of it reaches the developer.

## Children

The app is intended for licensed amateur radio operators and is not directed at children.

## Changes

Any change to this policy will be published in this file in the project repository, with an
updated date at the top.

## Contact

Luis Almonte (HI3LAG) — <https://www.qrz.com/db/HI3LAG>

Issues: the [issue tracker](../../issues) of this repository.
