# Changelog

All notable changes to RadioChat APRS are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- GPL-3.0-or-later licensing: `LICENSE`, per-file headers, and a name/logo reservation.
- Project documentation for the public repository: `CONTRIBUTING.md`, `PRIVACY.md`,
  `CHANGELOG.md`, issue templates and `FUNDING.yml`.

### Changed

- The whole user interface is now in English. Screen titles, settings, chat, map, logs,
  notifications, connection states and error messages were translated from Spanish.
- Source comments and log messages translated to English.
- **Renamed the application package from `com.aprs.radtel` to `com.aprs.radiochat`**, and
  the classes that carried the radio manufacturer's brand (`AprsRadtelApp` →
  `RadioChatApp`, `AprsRadtelAppNav` → `RadioChatAppNav`, `AprsRadtelTheme` →
  `RadioChatTheme`, `Theme.AprsRadtel` → `Theme.RadioChat`). *Radtel* is a third party's
  trademark and does not belong in this app's own identity; the app still supports the
  RT-950 Pro exactly as before, and still says so.
- The SharedPreferences store moved from `aprs_radtel` to `radiochat_aprs`.

> **Upgrading from an earlier build:** the new application id makes this a separate app to
> Android. It installs alongside the old one instead of replacing it, and settings and chat
> history do not carry over. Uninstall the old build once you have set the new one up.

## [1.0.0]

First release.

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

[Unreleased]: ../../compare/v1.0.0...HEAD
[1.0.0]: ../../releases/tag/v1.0.0
