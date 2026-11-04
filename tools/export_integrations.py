#!/usr/bin/env python3
"""Build portable MCP and Skill ZIPs using only explicitly listed source files."""
import argparse
import hashlib
from pathlib import Path
import sys
from zipfile import ZipFile, ZIP_DEFLATED

import sync_skill

ROOT = Path(__file__).resolve().parents[1]
PACKAGES = {
    "phoneuse-mcp": {
        "LICENSE": "LICENSE",
        "phoneuse_client.py": "tools/phoneuse_client.py",
        "phoneuse_adb.py": "tools/phoneuse_adb.py",
        "phoneuse_catalog.json": "tools/phoneuse_catalog.json",
        "configure.py": "integrations/mcp/configure.py",
        "README.md": "integrations/mcp/README.md",
        "README.zh-CN.md": "integrations/mcp/README.zh-CN.md",
    },
    "phone-use": {
        "LICENSE": "LICENSE",
        "SKILL.md": "skills/phone-use/SKILL.md",
        "README.zh-CN.md": "skills/phone-use/README.zh-CN.md",
        "agents/openai.yaml": "skills/phone-use/agents/openai.yaml",
        "scripts/phoneuse_client.py": "skills/phone-use/scripts/phoneuse_client.py",
        "scripts/phoneuse_adb.py": "skills/phone-use/scripts/phoneuse_adb.py",
        "scripts/phoneuse_catalog.json": "skills/phone-use/scripts/phoneuse_catalog.json",
        "references/api.md": "skills/phone-use/references/api.md",
        "references/api.zh-CN.md": "skills/phone-use/references/api.zh-CN.md",
        "references/transport-security.md": "skills/phone-use/references/transport-security.md",
        "references/transport-security.zh-CN.md": "skills/phone-use/references/transport-security.zh-CN.md",
    },
}


def export(destination):
    sync_skill.require_skill()
    destination = destination.expanduser().resolve()
    # Read first, so missing sources cannot produce a partially assembled package.
    contents = {name: {target: (ROOT / source).read_bytes() for target, source in files.items()}
                for name, files in PACKAGES.items()}
    artifacts = []
    for name, files in contents.items():
        for target, data in files.items():
            path = destination / name / target
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        archive = destination / (name + ".zip")
        # Never glob output folders: user credentials, screenshots, or old files
        # added beside the scripts must not enter a distributable archive.
        with ZipFile(archive, "w", ZIP_DEFLATED) as bundle:
            for target, data in files.items():
                bundle.writestr(f"{name}/{target}", data)
        artifacts.append(archive)
    # Installation guides are renamed in the distribution; preserve language links.
    for source, target, sibling, translated_sibling in (
        ("integrations.md", "INSTALL.md", "integrations.zh-CN.md", "INSTALL.zh-CN.md"),
        ("integrations.zh-CN.md", "INSTALL.zh-CN.md", "integrations.md", "INSTALL.md"),
    ):
        guide = (ROOT / "docs" / source).read_text(encoding="utf-8")
        (destination / target).write_text(
            guide.replace(f"]({sibling})", f"]({translated_sibling})"),
            encoding="utf-8", newline="\n",
        )
    (destination / "SHA256SUMS.txt").write_text("".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in artifacts), encoding="utf-8")
    return artifacts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "dist" / "integrations")
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    for artifact in export(args.output):
        print(artifact)


if __name__ == "__main__":
    main()
