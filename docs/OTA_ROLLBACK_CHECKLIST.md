# OTA write checklist and rollback

Applies to **every** firmware write to **your own** SY01. Each write needs your explicit decision **for that exact SHA-256**. **You can brick the ring**
(see [FIRMWARE.md](FIRMWARE.md)): no recovery from an image that boots but breaks BLE has been demonstrated.

Order of writes: **#1 is always the unmodified stock image** the ring already runs (V2.1.4), to prove the whole path on your ring. Only then a custom build.

## Images

| Image | How you get it | SHA-256 |
|---|---|---|
| Stock V2.1.4 (model 30330000), 174,952 B | Your own copy from the vendor app/CDN ([FIRMWARE.md](FIRMWARE.md)) | `8fb5ee3759dea842305cb23b9be2fd269db91d1a9ff04e3cd1578661fa531e50` |
| Custom build N | `python3 fw/ring_app/build_image.py <your stock image>` | printed by the builder; must be in `PxiOtaSession.ALLOWED_SHA256` |

Never flash an image whose hash you did not verify. Never flash an image you did not build or obtain yourself.

## Pre-flight (all must be true)

1. The ring is on its charger or recently charged (stock OTA wants >= 30 %; static finding).
2. The vendor app (RWFit) is fully closed (force-stopped); only our app talks to the ring.
3. Our app -> connect. The log must show `Chip-path check: JieLi ae00=false, PixArt-OTA ff00=true` and `Firmware v2.1.4 model=30330000`.
   **If the version or model differs, stop**: you would not be re-flashing the image this checklist covers.
4. Phone within ~30 cm of the ring, screen kept on. Session logging is on by default (`files/sessions/*.tsv`).
5. Both the target image **and** the stock image are staged in `Android/data/com.smartring.testbed/files/firmware/`, and the hash shown in the confirm dialog matches the table.
6. For a custom image: `tools/check_all.sh --fw` passed with your image (host tests, builder invariants, emulator run).
7. You understand the residual risk below and accept it for **this** ring.

## Procedure

Developer mode -> Ring tab -> Firmware -> select image -> type the first 8 hash characters -> Update. The app logs progress every 5 %.
The client sends only INIT_NEW / OBJECT_CREATE / data / UPGRADE / MCU_RESET, checks the ring's running 16-bit sum after every burst and aborts on mismatch,
and sends MCU_RESET only after reading back `0E 02 18 00` from the ring.

## Expected outcomes and what to do

| Situation | Expected state | Action |
|---|---|---|
| Abort, timeout or disconnect before verify | Running image untouched (inference: the update writes the other slot; interrupting it has not been tested) | Reconnect; confirm the version; pull the session log. |
| Verify result `FF` | Ring rejected the image; no reset sent | Same as above; check size and sum. |
| Verify OK -> reset -> ring reboots | New image active | The app reconnects by itself; confirm the version, touch, and the features you changed. |
| Ring does not come back after the reset | **Unverified case**: possible ROM fallback, or a stranded slot | Leave it on the charger for 2 minutes, power-cycle it if you can, rescan. If still dead, recovery would need opening the ring for a debug port, which we have not shown to be possible. |

## Rolling back

App -> Firmware -> "Stock V2.1.4 (restore)" -> first 8 characters of the stock hash. This is expected to work as long as the custom image boots and BLE is up (updating custom -> custom worked repeatedly; custom -> stock was not separately recorded).
That is also why every custom image keeps the OTA path byte-identical to stock.

## After a successful write

- Keep the session log. Record what you observed as *verified on your ring* in your own notes or in a PR.
- Each new custom image is a new hash: new allowlist entry, new run of this checklist.

## Build history of images prepared this way

The table is the history of what each build changed; hashes are of images derived from the vendor image, which this repo cannot distribute. Builds are listed in the
[CHANGELOG](../CHANGELOG.md). In short: build 1 (first mouse, proved the hook approach), build 2 (RAM-overlap fix, command hook), builds 3-4 (heart), 5-8 (recorder; a timer-struct overlap
was the root cause of the empty recordings and led to the emulator wild-write guard), 9-11 (fast sensor, gesture, custom message), 12-13 (touch modes, tilt mouse, gesture v2),
14 (pointer mouse; **withdrawn** after a bad RAM address broke the link), 15 (fix + wild-write guard), 16 (keys mode).
