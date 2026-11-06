# SPDX-License-Identifier: GPL-3.0-or-later
"""Acquire licensed, pinned SDK source; never fall back to template binaries."""
from pathlib import Path
import subprocess
import re
import os
import base64

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / ".dependencies" / "sdk"
URL = "https://git.tak.gov/weartak-plugins/weartak-plugin-sdk.git"


def prepare():
    revision = (ROOT / "sdk-revision.txt").read_text().strip()
    if not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise RuntimeError("Invalid SDK revision.")
    if not TARGET.exists():
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        environment = os.environ.copy()
        environment["GIT_TERMINAL_PROMPT"] = "0"
        if environment.get("CI_JOB_TOKEN"):
            authorization = base64.b64encode(("gitlab-ci-token:" + environment["CI_JOB_TOKEN"]).encode()).decode()
            existing = environment.get("GIT_CONFIG_PARAMETERS", "")
            environment["GIT_CONFIG_PARAMETERS"] = (existing + " " if existing else "") + (
                "'http.https://git.tak.gov/.extraHeader=Authorization: Basic " + authorization + "'"
            )
        subprocess.run(["git", "clone", "--quiet", "--no-checkout", URL, str(TARGET)], check=True, env=environment)
        subprocess.run(["git", "-C", str(TARGET), "checkout", "--quiet", "--detach", revision], check=True)
    actual = subprocess.check_output(["git", "-C", str(TARGET), "rev-parse", "HEAD"], text=True).strip()
    dirty = subprocess.check_output(["git", "-C", str(TARGET), "status", "--porcelain", "--untracked-files=all"], text=True).strip()
    if actual != revision or dirty:
        raise RuntimeError("SDK checkout differs from pin. Preserve its changes; supply a clean pinned checkout.")
    if not (TARGET / "LICENSE").is_file():
        raise RuntimeError("SDK license missing.")
    print("Pinned SDK source and license verified.")


if __name__ == "__main__":
    prepare()
