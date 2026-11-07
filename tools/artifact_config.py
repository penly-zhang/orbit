"""Shared names for the APK build and its LAN delivery directory."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
VERSION = re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT / "app/build.gradle.kts").read_text()).group(1)
assert re.fullmatch(r"\d+\.\d+\.\d+(?:-[a-z0-9.]+)?", VERSION)
ARTIFACT = ROOT / ("artifacts/ssh-v" + VERSION)
APK = "orbit-" + VERSION + "-debug.apk"
ZIP = "orbit-" + VERSION + ".zip"
