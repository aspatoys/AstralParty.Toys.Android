#!/usr/bin/env python3
"""UnityFS 探针：解析安卓热更缓存里的 bundle，列出里面的资源，抠出托管程序集。

只处理"块可解压"的常规 bundle（LZ4 / LZ4HC / LZMA / 不压缩）。
不写回——改写是 CesiumBundle 的事，这里先把结构看清楚。

用法：
    python tools/unityfs_probe.py <bundle>
    python tools/unityfs_probe.py <bundle> --dump <pathId|index> <outfile>
    python tools/unityfs_probe.py <bundle> --scan          # 对解压后的内容做 PE 扫描
"""

from __future__ import annotations

import argparse
import hashlib
import lzma
import struct
import sys

CLASS_NAMES = {
    1: "GameObject", 4: "Transform", 21: "Material", 28: "Texture2D", 43: "Mesh",
    48: "Shader", 49: "TextAsset", 83: "AudioClip", 114: "MonoBehaviour", 115: "MonoScript",
    128: "Font", 142: "AssetBundle", 213: "Sprite", 222: "CanvasRenderer",
}


# ---------------------------------------------------------------- LZ4 block
def lz4_block_decompress(src: bytes, expected: int) -> bytes:
    dst = bytearray()
    index = 0
    length = len(src)
    while index < length:
        token = src[index]
        index += 1

        literal = token >> 4
        if literal == 15:
            while index < length:
                extra = src[index]
                index += 1
                literal += extra
                if extra != 255:
                    break
        if index + literal > length:
            raise ValueError("LZ4 字面量越界")
        dst += src[index:index + literal]
        index += literal
        if index >= length:
            break

        if index + 2 > length:
            raise ValueError("LZ4 偏移越界")
        offset = src[index] | (src[index + 1] << 8)
        index += 2
        if offset == 0 or offset > len(dst):
            raise ValueError("LZ4 偏移非法")

        match = token & 0x0F
        if match == 15:
            while index < length:
                extra = src[index]
                index += 1
                match += extra
                if extra != 255:
                    break
        match += 4

        start = len(dst) - offset
        for step in range(match):
            dst.append(dst[start + step])

    if expected and len(dst) != expected:
        raise ValueError(f"LZ4 解压长度不符：得到 {len(dst)}，期望 {expected}")
    return bytes(dst)


# ------------------------------------------------------------------ LZMA
def lzma_block_decompress(src: bytes, expected: int) -> bytes:
    props = src[0]
    lc = props % 9
    rest = props // 9
    lp = rest % 5
    pb = rest // 5
    dict_size = struct.unpack_from("<I", src, 1)[0]
    payload = src[5:]
    filters = [{"id": lzma.FILTER_LZMA1, "dict_size": dict_size or 1 << 20, "lc": lc, "lp": lp, "pb": pb}]
    data = lzma.LZMADecompressor(format=lzma.FORMAT_RAW, filters=filters).decompress(payload)
    if expected and len(data) != expected:
        raise ValueError(f"LZMA 解压长度不符：得到 {len(data)}，期望 {expected}")
    return data


def decompress(src: bytes, expected: int, mode: int) -> bytes:
    if mode == 0:
        return src
    if mode in (2, 3):  # LZ4 / LZ4HC
        return lz4_block_decompress(src, expected)
    if mode in (1, 4):  # LZMA
        return lzma_block_decompress(src, expected)
    raise ValueError(f"不支持的压缩类型 {mode}")


def cstr(buffer: bytes, offset: int) -> tuple[str, int]:
    end = buffer.index(b"\0", offset)
    return buffer[offset:end].decode("utf-8", "replace"), end + 1


# -------------------------------------------------------------- bundle 解析
def read_bundle(blob: bytes) -> dict:
    if not blob.startswith(b"UnityFS\0"):
        raise ValueError("不是 UnityFS bundle")
    offset = 8
    (version,) = struct.unpack_from(">I", blob, offset)
    offset += 4
    unity_version, offset = cstr(blob, offset)
    unity_revision, offset = cstr(blob, offset)

    # 兼容 32/64 位 size 字段
    size64 = struct.unpack_from(">q", blob, offset)[0]
    offset += 8
    (blocks_info_size,) = struct.unpack_from(">I", blob, offset)
    offset += 4
    (blocks_info_uncompressed,) = struct.unpack_from(">I", blob, offset)
    offset += 4
    (flags,) = struct.unpack_from(">I", blob, offset)
    offset += 4

    header = {
        "version": version,
        "unityVersion": unity_version,
        "unityRevision": unity_revision,
        "declaredSize": size64,
        "blocksInfoSize": blocks_info_size,
        "blocksInfoUncompressed": blocks_info_uncompressed,
        "flags": flags,
        "dataStart": offset,
    }

    info_at_end = bool(flags & 0x80)
    if info_at_end:
        start = len(blob) - blocks_info_size
        info = blob[start:start + blocks_info_size]
        header["dataStart"] = offset
    else:
        info = blob[offset:offset + blocks_info_size]
        header["dataStart"] = offset + blocks_info_size

    compression = flags & 0x3F
    if blocks_info_size != blocks_info_uncompressed:
        info = decompress(info, blocks_info_uncompressed, compression)

    cursor = 0
    (block_count,) = struct.unpack_from(">i", info, cursor)
    cursor += 4
    blocks = []
    for _ in range(block_count):
        raw, comp, block_flags = struct.unpack_from(">IIH", info, cursor)
        cursor += 10
        blocks.append({"raw": raw, "comp": comp, "flags": block_flags})
    header["blocks"] = blocks

    (node_count,) = struct.unpack_from(">i", info, cursor)
    cursor += 4
    nodes = []
    for _ in range(node_count):
        node_offset, node_size, node_flags = struct.unpack_from(">qqI", info, cursor)
        cursor += 20
        path, cursor = cstr(info, cursor)
        nodes.append({"offset": node_offset, "size": node_size, "flags": node_flags, "path": path})
    header["nodes"] = nodes

    # 解压数据块
    data = bytearray()
    cursor = header["dataStart"]
    for block in blocks:
        chunk = blob[cursor:cursor + block["comp"]]
        cursor += block["comp"]
        data += decompress(chunk, block["raw"], block["flags"] & 0x3F)
    header["data"] = bytes(data)
    return header


def parse_serialized(data: bytes) -> dict:
    (metadata_size,) = struct.unpack_from(">I", data, 0)
    (file_size,) = struct.unpack_from(">I", data, 4)
    (version,) = struct.unpack_from(">I", data, 8)
    (data_offset,) = struct.unpack_from(">I", data, 12)
    header = {
        "metadataSize": metadata_size,
        "fileSize": file_size,
        "version": version,
        "dataOffset": data_offset,
    }

    if version >= 22:
        (count,) = struct.unpack_from(">i", data, 16 + 4 + 4 + 8 + 4 + 8 + 1 + 3)
        cursor = 16 + 4 + 4 + 8 + 4 + 8 + 1 + 3 + 4
    else:
        (count,) = struct.unpack_from(">i", data, 16)
        cursor = 20

    objects = []
    for _ in range(count):
        if version >= 22:
            path_id, byte_start, byte_size, type_id = struct.unpack_from(">qqIi", data, cursor)
            cursor += 24
        else:
            path_id, byte_start, byte_size, type_id = struct.unpack_from(">qiIi", data, cursor)
            cursor += 20
        objects.append({
            "pathId": path_id,
            "offset": byte_start,
            "size": byte_size,
            "typeId": type_id,
            "classId": type_id & 0x7FFFFFFF,
        })
    header["objects"] = objects
    return header


def object_payload(data: bytes, obj: dict) -> bytes:
    return data[obj["offset"]:obj["offset"] + obj["size"]]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle")
    parser.add_argument("--dump", nargs=2, metavar=("PATHID", "OUT"))
    parser.add_argument("--scan", action="store_true")
    args = parser.parse_args()

    blob = open(args.bundle, "rb").read()
    bundle = read_bundle(blob)

    print(f"bundle   : {args.bundle}  {len(blob)} B  sha256={hashlib.sha256(blob).hexdigest()}")
    print(f"unity    : {bundle['unityVersion']} ({bundle['unityRevision']})  format v{bundle['version']}")
    print(f"flags    : 0x{bundle['flags']:x}  压缩={ {0:'none',1:'lzma',2:'lz4',3:'lz4hc',4:'lzma'}.get(bundle['flags'] & 0x3f, '?')}")
    print(f"blocks   : {len(bundle['blocks'])}  解压后 {len(bundle['data'])} B")
    for node in bundle["nodes"]:
        print(f"  node  {node['path']}  offset={node['offset']} size={node['size']}")

    inner = bundle["data"]
    if bundle["nodes"]:
        node = bundle["nodes"][0]
        inner = bundle["data"][node["offset"]:node["offset"] + node["size"]]

    serialized = parse_serialized(inner)
    print(f"serialized: v{serialized['version']} metadata={serialized['metadataSize']} dataOffset={serialized['dataOffset']} objects={len(serialized['objects'])}")

    for index, obj in enumerate(serialized["objects"]):
        payload = object_payload(inner, obj)
        name = CLASS_NAMES.get(obj["classId"], str(obj["classId"]))
        marker = " MZ" if payload[:2] == b"MZ" else ""
        print(f"  [{index:3}] pathId={obj['pathId']:<20} {name:<14} off={obj['offset']:<10} size={obj['size']:<10}{marker}")

    if args.dump:
        want = int(args.dump[0])
        match = None
        for index, obj in enumerate(serialized["objects"]):
            if obj["pathId"] == want or index == want:
                match = obj
                break
        if match is None:
            print("找不到对象", file=sys.stderr)
            return 1
        payload = object_payload(inner, match)
        open(args.dump[1], "wb").write(payload)
        print(f"写出 {len(payload)} B -> {args.dump[1]}  sha256={hashlib.sha256(payload).hexdigest()}")
        return 0

    if args.scan:
        hits = [o for o in serialized["objects"] if object_payload(inner, o)[:2] == b"MZ"]
        print(f"托管程序集对象: {len(hits)}")
        for obj in hits:
            payload = object_payload(inner, obj)
            print(f"  pathId={obj['pathId']} size={obj['size']} sha256={hashlib.sha256(payload).hexdigest()}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
