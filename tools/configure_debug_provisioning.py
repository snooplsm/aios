#!/usr/bin/env python3
"""Generate a gitignored resource overlay for local debug provisioning."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
from xml.sax.saxutils import escape


ANDROID_BP = """runtime_resource_overlay {
    name: "AiosDebugProvisioningOverlay",
    resource_dirs: ["res"],
    certificate: "platform",
    manifest: "AndroidManifest.xml",
    product_specific: true,
}
"""

MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.aios.debugprovisioning.overlay">
    <application android:hasCode="false" />
    <overlay
        android:isStatic="true"
        android:priority="1000"
        android:targetPackage="com.aios.developerdefaults" />
</manifest>
"""


def generate(output: Path, ssid: str, psk: str) -> None:
    if not ssid:
        raise ValueError("SSID must not be empty")
    if not 8 <= len(psk) <= 63:
        raise ValueError("WPA passphrase must be between 8 and 63 characters")
    if output.exists():
        raise FileExistsError(f"refusing to overwrite {output}")
    values = output / "res" / "values"
    values.mkdir(parents=True)
    (output / "Android.bp").write_text(ANDROID_BP, encoding="utf-8")
    (output / "AndroidManifest.xml").write_text(MANIFEST, encoding="utf-8")
    (values / "config.xml").write_text(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        "<resources>\n"
        "    <bool name=\"debug_instant_provisioning\">true</bool>\n"
        f"    <string name=\"debug_wifi_ssid\" translatable=\"false\">{escape(ssid)}</string>\n"
        f"    <string name=\"debug_wifi_psk\" translatable=\"false\">{escape(psk)}</string>\n"
        "</resources>\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ssid", required=True)
    parser.add_argument("--psk-env", default="AIOS_DEBUG_WIFI_PSK")
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    psk = os.environ.get(arguments.psk_env)
    if psk is None:
        parser.error(f"environment variable {arguments.psk_env} is not set")
    generate(arguments.output, arguments.ssid, psk)
    print(f"generated local debug provisioning overlay at {arguments.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
