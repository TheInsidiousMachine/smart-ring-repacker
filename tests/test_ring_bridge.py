"""PC bridge key mapping: ydotool gets real key codes (press in order, release in reverse)."""
import importlib.util
import os

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("ring_bridge", os.path.join(HERE, "..", "pc", "ring_bridge.py"))
rb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rb)


def test_ctrl_c():
    assert rb.ydotool_key_args("ctrl+c") == ["ydotool", "key", "29:1", "46:1", "46:0", "29:0"]


def test_three_keys_and_aliases():
    assert rb.ydotool_key_args("Ctrl + Alt + T") == ["ydotool", "key", "29:1", "56:1", "20:1", "20:0", "56:0", "29:0"]
    assert rb.ydotool_key_args("super+f5") == ["ydotool", "key", "125:1", "63:1", "63:0", "125:0"]
    assert rb.ydotool_key_args("playpause") == ["ydotool", "key", "164:1", "164:0"]


def test_unknown_key_is_refused():
    assert rb.ydotool_key_args("ctrl+notakey") is None
    assert rb.ydotool_key_args("") is None


def test_table_matches_evdev():
    ecodes = pytest.importorskip("evdev").ecodes
    for name, code in rb.LINUX_KEYCODES.items():
        assert getattr(ecodes, name) == code, name
