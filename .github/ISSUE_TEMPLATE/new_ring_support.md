---
name: New ring support
about: Propose or track support for another smart ring
labels: new-ring
---

Please read docs/ADDING_A_RING.md first.

**Ring model / vendor app / chip**

**Do you own one and can you test on real hardware?** (yes / no). Untested code is welcome but is labelled unverified.

**BLE facts you already have** (advertised name pattern, service and characteristic UUIDs, packet framing/checksum, links to public write-ups). Mark what you verified yourself.

**Does the ring have a touch / button?** What events does it send (tap, press, release, hold)? An external keyboard app needs press and release edges; rings that only report taps cannot be a gaze-keyboard button.

**Other features you care about** (battery, clock, display, motion stream, heart rate).

**Willing to open a PR for the `RingController` implementation?**
