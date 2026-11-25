# Security

## Reporting a vulnerability

Please report privately through GitHub's **"Report a vulnerability"** (Security tab -> Advisories) on this repository. If that is unavailable, open an issue that says only that you
have a security report and ask for a private channel; do not post details publicly. We aim to acknowledge within a few days. This is a volunteer project without a bug bounty.

## What the app exposes

The Android app can run a local HTTP + WebSocket server ([docs/API.md](docs/API.md)). It is **off by default** and its settings are not persisted, so it is off again after the app restarts.

- **Bearer token.** Every endpoint, including both WebSockets, requires it. The token is a random UUID (122 bits) generated when you first enable the API and shown in the app; compared in constant time. Regenerate it from the Gestures tab.
- **Plain HTTP, no TLS.** Anyone who can observe the traffic can read the token and events. A token in a WebSocket URL (`?token=`) can end up in logs; prefer the header.
- **LAN exposure.** "LAN" starts **off** (loopback only, `127.0.0.1`). With "LAN" on, the server listens on `0.0.0.0` (all interfaces) so any device on the same network (or VPN) that has the token can control the ring (show text, switch modes, read gestures
  and touch events, stream raw motion). Leave "LAN" off unless another device needs the API. Do not enable it on untrusted Wi-Fi, and do not port-forward it to the internet.
- **No per-client limits or rate limiting.** There is no brute-force protection beyond the token's length.
- **Webhook actions** make HTTP requests to URLs you configure, and the PC bridge turns `pc_keys` events into real keystrokes on your computer. Anyone with the token and a phone-side gesture setup could cause key presses on a machine running the bridge; run the bridge only with a token you control, and look at what actions are configured.
- **Local data.** Gesture examples, logs and recordings (raw accelerometer data) are stored in the app's private and `Android/data/...` directories. Session logs contain raw BLE traffic and the ring's address. Redact them before sharing. The manifest has `allowBackup="true"`, which means app data (including saved gestures) can be included in device backups.

## Firmware safety

Flashing firmware can brick a ring ([docs/FIRMWARE.md](docs/FIRMWARE.md)). The updater is gated by a hash allowlist, a typed confirmation and a verify-before-reset rule, but those protect against mistakes, not against a malicious image you chose to build and flash.
We have not assessed the security of the ring's own BLE link (pairing, encryption). Treat it as potentially open to anyone in radio range who connects first. **Unverified.**

## Scope

This project is for ring(s) you own. Please do not use it, or reports about it, to access other people's devices.
