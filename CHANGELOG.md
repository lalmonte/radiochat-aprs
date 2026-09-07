# Changelog

All notable changes to RadioChat APRS are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

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
