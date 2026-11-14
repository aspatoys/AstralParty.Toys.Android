#!/usr/bin/env python3
"""在二进制块里扫描内嵌的托管程序集。

用途：从 Unity 缓存块（`__data`）里把 `AstralParty.Runtime.dll` 这类内嵌 DLL
原样抠出来，算 SHA-256，供与桌面 `refs/game` 比对。

不做完整 Unity 解析——先回答「这块里有没有 PE、是不是 .NET、多大、哈希多少」，
比一上来就写 bundle 解析器便宜得多。

用法：
    python tools/pe_blob_scan.py tests/fixtures/real/p0-xxx/<key>/__data
    python tools/pe_blob_scan.py <dir> --json
    python tools/pe_blob_scan.py <blob> --dump out/
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
import sys

MIN_SIZE = 1024
MAX_SECTIONS = 96


def pe_extent(blob: bytes, offset: int) -> int | None:
    """返回从 offset 开始的 PE 镜像长度（按节表推导），失败返回 None。"""
    if offset + 0x40 > len(blob):
        return None
    if blob[offset:offset + 2] != b"MZ":
        return None

    (lfanew,) = struct.unpack_from("<I", blob, offset + 0x3C)
    pe = offset + lfanew
    if pe + 24 > len(blob) or blob[pe:pe + 4] != b"PE\0\0":
        return None

    (num_sections,) = struct.unpack_from("<H", blob, pe + 6)
    (size_of_optional,) = struct.unpack_from("<H", blob, pe + 20)
    if not 1 <= num_sections <= MAX_SECTIONS:
        return None
    if not 96 <= size_of_optional <= 512:
        return None

    optional = pe + 24
    if optional + 64 > len(blob):
        return None
    (size_of_headers,) = struct.unpack_from("<I", blob, optional + 60)
    if not 0x40 <= size_of_headers <= len(blob):
        return None

    sections = optional + size_of_optional
    extent = size_of_headers
    for index in range(num_sections):
        entry = sections + index * 40
        if entry + 40 > len(blob):
            return None
        (raw_size,) = struct.unpack_from("<I", blob, entry + 16)
        (raw_ptr,) = struct.unpack_from("<I", blob, entry + 20)
        if raw_size and raw_ptr:
            extent = max(extent, raw_ptr + raw_size)

    if extent < MIN_SIZE or offset + extent > len(blob):
        return None
    return extent


def describe_strings(image: bytes) -> list[str]:
    """在镜像里找形如 Xxx.dll / Xxx.exe 的名字，用来判断是哪个程序集。"""
    found: list[str] = []
    for marker in (b".dll\x00", b".exe\x00"):
        start = 0
        while True:
            hit = image.find(marker, start)
            if hit < 0:
                break
            start = hit + 1
            end = hit + len(marker) - 1
            begin = hit
            while begin > 0 and 32 <= image[begin - 1] < 127:
                begin -= 1
            name = image[begin:end].decode("ascii", "ignore")
            if 3 <= len(name) <= 96 and name not in found:
                found.append(name)
    return found


def scan(blob: bytes, source: str) -> list[dict]:
    results: list[dict] = []
    seen: set[int] = set()
    start = 0
    while True:
        hit = blob.find(b"MZ", start)
        if hit < 0:
            break
        start = hit + 2
        if hit in seen:
            continue
        extent = pe_extent(blob, hit)
        if extent is None:
            continue
        seen.add(hit)
        image = blob[hit:hit + extent]
        results.append(
            {
                "source": source,
                "offset": hit,
                "size": extent,
                "sha256": hashlib.sha256(image).hexdigest(),
                "is_dotnet": b"BSJB" in image,
                "names": describe_strings(image)[:8],
            }
        )
    return results


def scan_path(path: str) -> list[dict]:
    if os.path.isdir(path):
        results: list[dict] = []
        for name in sorted(os.listdir(path)):
            full = os.path.join(path, name)
            if os.path.isfile(full):
                with open(full, "rb") as handle:
                    results.extend(scan(handle.read(), full))
        return results
    with open(path, "rb") as handle:
        return scan(handle.read(), path)


def main() -> int:
    parser = argparse.ArgumentParser(description="扫描二进制块里内嵌的 PE / .NET 程序集")
    parser.add_argument("path", help="文件或目录")
    parser.add_argument("--json", action="store_true", help="输出 JSON")
    parser.add_argument("--dump", metavar="DIR", help="把命中的镜像写出来")
    parser.add_argument("--name", help="只保留名字包含该子串的结果（大小写不敏感）")
    args = parser.parse_args()

    results = scan_path(args.path)
    if args.name:
        needle = args.name.lower()
        results = [item for item in results if any(needle in name.lower() for name in item["names"])]

    if args.dump:
        os.makedirs(args.dump, exist_ok=True)
    for item in results:
        if not args.dump:
            continue
        label = item["names"][0] if item["names"] else "image"
        target = os.path.join(args.dump, f"{item['sha256'][:12]}-{label}")
        with open(args.path, "rb") as handle:
            handle.seek(item["offset"])
            data = handle.read(item["size"])
        with open(target, "wb") as out:
            out.write(data)

    if args.json:
        print(json.dumps(results, ensure_ascii=False, indent=2))
        return 0

    if not results:
        print(f"{args.path}: 没有找到 PE 镜像（块可能是压缩的，或不是托管程序集）")
        return 1

    for item in results:
        kind = ".NET" if item["is_dotnet"] else "native?"
        names = ", ".join(item["names"]) if item["names"] else "(未识别)"
        print(
            f"{os.path.basename(item['source'])} @{item['offset']:#x} "
            f"{item['size']} B {kind} sha256={item['sha256']} names={names}"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
