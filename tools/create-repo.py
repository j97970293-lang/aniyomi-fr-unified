#!/usr/bin/env python3
"""Create Aniyomi index.json/index.min.json from a built FR Unified APK."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from zipfile import ZipFile


def match(pattern: str, text: str, label: str) -> str:
    result = re.search(pattern, text, re.MULTILINE)
    if not result:
        raise RuntimeError(f"Unable to read {label} from aapt output")
    return result.group(1)


def source_id(name: str, lang: str, version_id: int) -> int:
    # NB : Aniyomi décode l'index avec kotlinx.serialization en mode strict :
    # le champ « id » doit être un NOMBRE JSON (Long), jamais une chaîne.
    digest = hashlib.md5(f"{name.lower()}/{lang}/{version_id}".encode()).digest()
    value = 0
    for index in range(8):
        value |= (digest[index] & 0xFF) << (8 * (7 - index))
    return value & 0x7FFFFFFFFFFFFFFF


def find_sdk_tool(name: str) -> Path:
    sdk = Path(os.environ["ANDROID_HOME"])
    candidates = sorted((sdk / "build-tools").glob(f"*/{name}"), reverse=True)
    if not candidates:
        raise RuntimeError(f"{name} not found under ANDROID_HOME/build-tools")
    return candidates[0]


def signing_fingerprint(apk: Path) -> str:
    result = subprocess.run(
        [str(find_sdk_tool("apksigner")), "verify", "--print-certs", str(apk)],
        text=True,
        capture_output=True,
        check=True,
    )
    # Build-tools releases have emitted the certificate report on either stream,
    # and some versions separate fingerprint octets with colons.
    output = result.stdout + "\n" + result.stderr
    fingerprint = match(
        r"certificate SHA-256 digest:\s*([0-9a-fA-F:]+)",
        output,
        "signing fingerprint",
    )
    return fingerprint.replace(":", "").lower()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--name", default="FR Unifié")
    parser.add_argument("--website", default="https://github.com/")
    args = parser.parse_args()

    apk = args.apk.resolve()
    output = args.output.resolve()
    apk_dir = output / "apk"
    icon_dir = output / "icon"
    apk_dir.mkdir(parents=True, exist_ok=True)
    icon_dir.mkdir(parents=True, exist_ok=True)

    badging = subprocess.check_output(
        [str(find_sdk_tool("aapt")), "dump", "--include-meta-data", "badging", str(apk)],
        text=True,
    )
    package_line = next(line for line in badging.splitlines() if line.startswith("package: "))
    package_name = match(r"name='([^']+)'", package_line, "package name")
    version_code = int(match(r"versionCode='([^']+)'", package_line, "version code"))
    version_name = match(r"versionName='([^']+)'", package_line, "version name")
    label = match(r"^application-label:'([^']+)'", badging, "application label")
    icon_path = match(r"^application-icon-320:'([^']+)'", badging, "application icon")
    names = match(r"'tachiyomi\.animeextension\.names' value='([^']+)'", badging, "source names").split(";")
    version_id = int(
        match(r"'tachiyomi\.animeextension\.versionId' value='([^']+)'", badging, "version id"),
    )
    nsfw = int(match(r"'tachiyomi\.animeextension\.nsfw' value='([^']+)'", badging, "NSFW flag"))
    lang = package_name.split(".animeextension.", 1)[1].split(".", 1)[0]

    apk_name = apk.name.replace("-release", "").replace("-debug", "")
    shutil.copy2(apk, apk_dir / apk_name)
    with ZipFile(apk) as archive, archive.open(icon_path) as source, (icon_dir / f"{package_name}.png").open("wb") as target:
        shutil.copyfileobj(source, target)

    sources = [
        {
            "name": name,
            "lang": lang,
            "id": source_id(name, lang, version_id),
            "baseUrl": "",
            "versionId": version_id,
        }
        for name in names
    ]
    common = {
        "name": label,
        "pkg": package_name,
        "apk": apk_name,
        "lang": lang,
        "code": version_code,
        "version": version_name,
        "nsfw": nsfw,
        "sources": sources,
    }
    output.joinpath("index.json").write_text(
        json.dumps([{**common, "hasReadme": 0, "hasChangelog": 0}], ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    output.joinpath("index.min.json").write_text(
        json.dumps([common], ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    repo = {
        "meta": {
            "name": args.name,
            "website": args.website,
            "signingKeyFingerprint": signing_fingerprint(apk),
        },
    }
    output.joinpath("repo.json").write_text(
        json.dumps(repo, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Created repository for {package_name} {version_name} in {output}")


if __name__ == "__main__":
    main()
