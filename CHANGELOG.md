# Changelog

All notable changes to RadioChat APRS are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Radio selection for the Bluetooth TNC.** More → Links & network → Bluetooth now has a
  radio picker, and scanning filters for the model you chose.
- **BTECH UV-PRO support (experimental).** The UV-PRO and its siblings (Vero VR-N76,
  RadioOddity GA-5WB) do not speak KISS over BLE; they use the Benshi protocol, where
  AX.25 frames travel as fragmented "TNC data" messages over an *indication*
  characteristic. UUIDs, command identifiers and framing all follow the
  [benlink](https://github.com/khusmann/benlink) reference. **Receiving and transmitting
  are both confirmed on hardware.**
- The app now registers for radio events on connect. These radios report nothing until
  asked: registering `HT_STATUS_CHANGED` is what also enables `DATA_RXD`, the event that
  carries received frames. Without it the link comes up and no packet ever arrives.

- **Transmitting over Bluetooth, for radios that can.** Chat messages, ACKs and GPS
  beacons now go out through the radio's BLE TNC when the connected model supports it.
  The UV-PRO does; the RT-950 Pro is marked receive-only, because it accepts the write
  and never keys up — claiming otherwise would report messages as sent that never
  reached the air. Receive-only is still enough to run the app as an iGate, which never
  transmits on RF.
- ACKs now reply over the same link the message arrived on whenever that link can
  transmit, instead of falling back to the internet for a station that may not be there.

- **Resend for unconfirmed messages.** Long-pressing an outgoing message that has not
  been acknowledged offers *Send again*. The APRS message id is reused, so an ACK still
  matches the original bubble and a correspondent who already received it re-ACKs the
  duplicate rather than showing the text twice. Retries are counted and shown as `↻n`.

### Changed

- The BLE layer was split into a GATT state machine that knows nothing about any radio
  and a per-radio `BleRadioProfile` holding the attributes and the framing. Adding a model
  is now a new profile rather than a change to shared code.
- **The Radtel RT-950 Pro path is unchanged**: same UUIDs, same FF31 preference, same KISS
  framing, same MTU chunking. Unit tests now pin those down so a future radio cannot
  silently alter them.
- Switching radios drops any live BLE link, since the two protocols are not interchangeable.
- Unit tests return default values for unmocked Android stubs, so a stray `Log` call in
  code under test no longer fails an otherwise valid test.

## [1.0.0] — 2026-09-07

First public release.

### Added

- **Chat** — APRS text messaging (`:CALLSIGN :text{id`) with WhatsApp-style ACK ticks,
  persisted history, unread badges and swipe-to-delete.
- **Map** — live stations on OpenStreetMap (osmdroid) with Hessu APRS symbols, own position
  and a track of transmitted beacons.
- **Logs** — live packet monitor for RF and Internet traffic, with per-station symbols,
  filters and an optional RAW view.
- **Three transports** — BLE KISS (Radtel RT-950 Pro), KISS TCP (DireWolf or any KISS TNC)
  and APRS-IS, usable simultaneously.
- **GPS beacon** — periodic position beaconing over APRS-IS and/or KISS TCP, running in a
  location foreground service so it survives the screen going off.
- **iGate** — RF → APRS-IS forwarding with anti-loop, dedupe and rate limiting.
- **Notifications** — incoming messages, ACK/REJ and dropped links, per-channel.
- SSID-aware throughout: `HI3LAG-7` is not `HI3LAG-3`.

### Project

- Released as free software under **GPL-3.0-or-later**. The app name and icon are reserved;
  see the README.
- The user interface, source comments and log messages are in English.
- Application id `com.aprs.radiochat`. The package deliberately carries no third-party
  trademark; the app supports the Radtel RT-950 Pro and says so in its documentation.
- Signed release APKs are built automatically from a git tag, and published here with a
  SHA-256 checksum you can verify before installing.

[Unreleased]: ../../compare/v1.0.0...HEAD
[1.0.0]: ../../releases/tag/v1.0.0
