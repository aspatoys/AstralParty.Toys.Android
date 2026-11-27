#!/usr/bin/env python3
"""Check DEX classes in an APK for cross-package non-public superclass or interface access."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

CLASS_RE = re.compile(r"Class descriptor\s+:\s+'([^']+)'")
FLAGS_RE = re.compile(r"Access flags\s+:\s+0x([0-9a-fA-F]+)")
SUPER_RE = re.compile(r"Superclass\s+:\s+'([^']+)'")
IFACE_RE = re.compile(r"^\s+#\d+\s+:\s+'([^']+)'")

ACC_PUBLIC = 0x0001


def find_dexdump(explicit: str | None) -> str:
    if explicit:
        return explicit
    for env_key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(env_key)
        if sdk:
            build_tools = Path(sdk) / "build-tools"
            if build_tools.is_dir():
                for name in ("dexdump.exe", "dexdump"):
                    matches = sorted(build_tools.glob(f"*/{name}"))
                    if matches:
                        return str(matches[-1])
    for name in ("dexdump", "dexdump.exe"):
        found = shutil.which(name)
        if found:
            return found
    raise FileNotFoundError("Could not locate dexdump; pass --dexdump or set ANDROID_HOME.")


def package_of(descriptor: str) -> str:
    return descriptor.rsplit("/", 1)[0] if "/" in descriptor else ""


def parse_dex_dump(output: str, classes: dict[str, tuple[int, str | None, list[str]]]) -> None:
    current_desc: str | None = None
    current_flags = 0
    current_super: str | None = None
    current_ifaces: list[str] = []
    in_interfaces = False

    def flush() -> None:
        if current_desc is not None:
            classes[current_desc] = (current_flags, current_super, list(current_ifaces))

    for line in output.splitlines():
        stripped = line.strip()
        if stripped.startswith("Class #"):
            flush()
            current_desc = None
            current_flags = 0
            current_super = None
            current_ifaces = []
            in_interfaces = False
            continue

        m = CLASS_RE.search(line)
        if m:
            current_desc = m.group(1)
            continue

        m = FLAGS_RE.search(line)
        if m and current_desc is not None and current_super is None and not in_interfaces:
            current_flags = int(m.group(1), 16)
            continue

        m = SUPER_RE.search(line)
        if m:
            current_super = m.group(1)
            continue

        if stripped.startswith("Interfaces"):
            in_interfaces = True
            continue

        if in_interfaces:
            m = IFACE_RE.match(line)
            if m:
                current_ifaces.append(m.group(1))
            else:
                in_interfaces = False

    flush()


def check_apk(apk_path: Path, dexdump_bin: str) -> tuple[int, int, list[str]]:
    classes: dict[str, tuple[int, str | None, list[str]]] = {}
    with tempfile.TemporaryDirectory() as tmpdir:
        with zipfile.ZipFile(apk_path, "r") as zf:
            dex_names = sorted(
                n for n in zf.namelist()
                if re.fullmatch(r"classes\d*\.dex", n)
            )
            if not dex_names:
                raise RuntimeError(f"No classes*.dex found in {apk_path}")
            for dex_name in dex_names:
                out_path = Path(tmpdir) / dex_name
                out_path.write_bytes(zf.read(dex_name))
                proc = subprocess.run(
                    [dexdump_bin, str(out_path)],
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    check=True,
                )
                parse_dex_dump(proc.stdout, classes)

    violations: list[str] = []
    for cls, (flags, sup, ifaces) in classes.items():
        cls_pkg = package_of(cls)
        if sup and sup in classes:
            sup_flags, _, _ = classes[sup]
            if (sup_flags & ACC_PUBLIC) == 0 and package_of(sup) != cls_pkg:
                violations.append(
                    f"{cls} (flags=0x{flags:04x}) extends non-public superclass {sup} (flags=0x{sup_flags:04x})"
                )
        for iface in ifaces:
            if iface in classes:
                iface_flags, _, _ = classes[iface]
                if (iface_flags & ACC_PUBLIC) == 0 and package_of(iface) != cls_pkg:
                    violations.append(
                        f"{cls} (flags=0x{flags:04x}) implements non-public interface {iface} (flags=0x{iface_flags:04x})"
                    )
    return len(classes), len(dex_names), violations


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, help="Path to APK file to inspect")
    parser.add_argument("--dexdump", default=None, help="Optional path to dexdump binary")
    args = parser.parse_args()

    apk_path = Path(args.apk)
    if not apk_path.is_file():
        print(f"APK not found: {apk_path}", file=sys.stderr)
        return 2

    dexdump_bin = find_dexdump(args.dexdump)
    total_classes, dex_count, violations = check_apk(apk_path, dexdump_bin)
    print(f"Checked {total_classes} classes across {dex_count} dex file(s): {len(violations)} violation(s)")
    for item in violations:
        print(f"ERROR: {item}", file=sys.stderr)
    return 1 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main())
