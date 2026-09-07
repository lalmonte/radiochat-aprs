# Contributing to RadioChat APRS

Thanks for your interest. This is a spare-time amateur radio project, so please be patient
with review times.

## Before you start

- **Open an issue first** for anything larger than a bug fix. It saves you from building
  something that does not fit the app.
- By contributing, you agree that your work is licensed under **GPL-3.0-or-later**, the
  same terms as the rest of the project. There is no CLA and no copyright assignment.

## Reporting a bug

APRS bugs are hard to reproduce without context, so please include:

- Android version and phone model
- App version (**More → About**)
- Which transport was in use: BLE (RT-950 Pro), KISS TCP (DireWolf), or APRS-IS
- Your SSID setup, if it is relevant (for example: phone `HI3LAG-7`, DireWolf `HI3LAG-3`)
- The **raw packet** if you have it — enable the `RAW` chip in the Logs tab
- Relevant `adb logcat` output

Please redact anything you do not want public. Callsigns are public information on APRS,
but your exact home coordinates may not be something you want in an issue.

## Development setup

Android Studio (Ladybug / Koala or newer), JDK 17.

```bash
./gradlew :app:assembleDebug
./gradlew :app:test
```

There is no emulator path for the radio itself: BLE KISS needs a real RT-950 Pro. You can
develop and test most of the app against **DireWolf over KISS TCP** and **APRS-IS**, which
need no special hardware.

## Code style

Match the surrounding code. In practice:

- Kotlin official style, 4-space indent, ~100 column soft limit
- **Comments and identifiers in English**
- Comments should explain *why*, not *what*. Most existing comments document a decision or
  a bug that was fixed — keep that habit rather than narrating the code.
- Every source file carries the GPL header. Copy it into new files.

## Tests

Protocol code must come with unit tests. The codecs are the part of the app that is easy
to test and expensive to get wrong:

```
app/src/test/java/com/aprs/radiochat/
├── data/aprs/       AprsCodecTest
├── data/aprsis/     AprsIsCodecTest, AprsIsFilterTest, OwnTransmissionLogTest
├── data/kiss/       KissCodecTest
├── data/location/   TrackPointTest
└── data/model/      ChatAckTest, ChatConversationTest
```

Run `./gradlew :app:test` before opening a pull request.

## On-air behaviour

This app transmits on amateur radio frequencies and connects to shared infrastructure.
Changes that affect what goes on the air get extra scrutiny:

- Do not widen the APRS-IS filter in a way that pulls global traffic (never a lone `t/m`).
- Do not remove the iGate anti-loop, dedupe or rate-limit guards.
- Do not shorten the beacon interval floor (60 s) — a shared channel is not yours alone.
- The beacon must stay quiet on a stale GPS fix rather than announce a position that is
  no longer true.

## Translations

The UI strings are currently in English, mostly inline in the Compose code rather than in
`strings.xml`. If you want to add a language, open an issue first — the strings need to be
extracted into resources before translation makes sense, and that is worth coordinating so
two people do not do it twice.
