# Contributing to OnSpot

Thanks for taking the time to help. Here's how to get set up and what we look for in a change.

## Setup

| App     | Folder     | Tools                                   | Run                         |
|---------|------------|-----------------------------------------|-----------------------------|
| Windows | `desktop/` | Node.js 18+                             | `npm install && npm start`  |
| Android | `android/` | Android Studio, JDK 17                  | open the folder, run `app`  |

To test against a real device, put your phone and PC on the same Wi‑Fi (or connect the PC to the phone's hotspot).

## Before you open a pull request

- **Keep the protocol compatible.** Both apps must keep talking to older versions. If you need a protocol change, open an issue first and update [docs/PROTOCOL.md](docs/PROTOCOL.md) in the same PR.
- **Desktop:** run `npm test` in `desktop/`. New logic in `src/core/` should come with a test.
- **Android:** make sure the project builds with `./gradlew assembleDebug`.
- Keep pull requests focused. One fix or feature per PR is much easier to review.
- Match the surrounding code style, and leave comments that explain *why*, not *what*.

## Reporting bugs

Please include:

- which apps and versions (for example *Android 2.0 on a Pixel 7, Windows app 2.1.1*)
- the network setup: home Wi‑Fi, phone hotspot, office network, VPN on or off
- what you expected, and what happened instead
- for Android crashes, the `FATAL EXCEPTION` lines from Logcat

## Security issues

Please don't open a public issue for a security problem. Email the maintainer instead (the address is on the GitHub profile), and we'll work on a fix together.
