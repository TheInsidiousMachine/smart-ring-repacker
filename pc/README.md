# Smart Ring Repacker PC Bridge (`ring_bridge.py`)

Companion daemon for Linux (developed on KDE Plasma Wayland; other desktops unverified) that connects to the Smart Ring Repacker Android app's local API (`/v1/events` WebSocket) and translates `pc_keys` gesture actions into real keyboard combinations.

## Why a Bridge?

The ring's own Bluetooth HID goes to the paired device (the phone). The phone recognizes custom trainable motion gestures and relays configured computer shortcuts (e.g., `ctrl+c`, `alt+tab`, `volumeup`) over the local network (Wi-Fi or a VPN such as Tailscale/WireGuard) to this PC companion daemon.

## Requirements

- Linux with `/dev/uinput` (standard on modern Linux kernels)
- Python 3.8+
- Optional: `python-evdev` for direct kernel UInput device synthesis (recommended):
  ```bash
  pip install evdev
  ```
- Optional fallback: `ydotool` (if `python-evdev` or `/dev/uinput` access is not configured). The bridge sends real key codes (`ydotool key 29:1 46:1 46:0 29:0` for `ctrl+c`); the `ydotoold` daemon must be running.

## UInput Permissions (Recommended)

To allow `ring_bridge.py` to create a virtual input device without root (`sudo`), grant user access to `/dev/uinput`:

1. Create a udev rule `/etc/udev/rules.d/99-uinput.rules`:
   ```udev
   KERNEL=="uinput", MODE="0660", GROUP="input", OPTIONS+="static_node=uinput"
   ```
2. Add your user to the `input` group:
   ```bash
   sudo usermod -a -G input $USER
   ```
3. Reload udev rules:
   ```bash
   sudo udevadm control --reload-rules && sudo udevadm trigger
   ```
4. Log out and back in for group membership to take effect.

## Usage

### Command Line

```bash
# Run with CLI arguments:
./pc/ring_bridge.py --url ws://<phone-ip>:8765/v1/events --token <your-bearer-token> -v

# Or configure via environment variables:
export REPACKER_URL="ws://<phone-ip>:8765/v1/events"
export REPACKER_TOKEN="<your-bearer-token>"
./pc/ring_bridge.py -v
```

### Supported Key Combinations

Key combos are configured in the Smart Ring Repacker app as `PcKeys` actions. Examples:
- Modifiers + key: `ctrl+c`, `ctrl+v`, `alt+tab`, `super+d`, `ctrl+alt+t`
- Media keys: `volumeup`, `volumedown`, `mute`, `playpause`, `next`, `prev`
- Navigation: `up`, `down`, `left`, `right`, `enter`, `escape`, `space`

### Running as a systemd User Service

To start the bridge automatically when logging in:

1. Create `~/.config/systemd/user/ring-bridge.service`:
   ```ini
   [Unit]
   Description=Smart Ring Repacker PC Input Bridge
   After=network.target

   [Service]
   Type=simple
   Environment=REPACKER_URL=ws://<phone-ip>:8765/v1/events
   Environment=REPACKER_TOKEN=YOUR_TOKEN_HERE
   ExecStart=/usr/bin/python3 /path/to/pc/ring_bridge.py
   Restart=always
   RestartSec=3

   [Install]
   WantedBy=default.target
   ```
2. Enable and start:
   ```bash
   systemctl --user daemon-reload
   systemctl --user enable --now ring-bridge.service
   ```
3. Check status:
   ```bash
   systemctl --user status ring-bridge.service
   ```
