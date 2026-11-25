# firmware/

**This directory intentionally contains no firmware.** Put your own copy of the vendor image here:

```
firmware/SY01_30330000_V2.1.4_stock.bin      (git-ignored: *.bin)
```

The vendor firmware is third-party material. Do not commit or redistribute it, or any image built from it.

| | |
|---|---|
| Ring model / version | `30330000`, V2.1.4 |
| Size | 174,952 bytes |
| SHA-256 | `8fb5ee3759dea842305cb23b9be2fd269db91d1a9ff04e3cd1578661fa531e50` |

Verify: `sha256sum -c SY01_30330000_V2.1.4_stock.bin.sha256` (run inside this directory).

How to get the image for **your own ring** through **your own** vendor-app account, how the builder checks it, and the brick risk:
[docs/FIRMWARE.md](../docs/FIRMWARE.md). `fw/ring_app/build_image.py` and `tools/analyze_stock_firmware.py` refuse any file whose hash differs.
