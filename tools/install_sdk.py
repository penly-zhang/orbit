"""Install the project's Android SDK packages using its private JDK 17."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
environment = dict(os.environ)
environment["JAVA_HOME"] = str(root / ".tools/amazon-corretto-17.jdk/Contents/Home")
sdk = root / ".tools/android-sdk"
command = [str(sdk / "cmdline-tools/latest/bin/sdkmanager"), "--sdk_root=" + str(sdk), "platforms;android-35", "build-tools;35.0.0", "platform-tools"]
subprocess.run(command, env=environment, input=b"y\n" * 100, check=True)
