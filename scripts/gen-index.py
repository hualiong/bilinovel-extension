#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""重新生成这个发布分支的插件索引。

新格式（Mihon 0.20+ 读取的 index.pb / repo.json 里的 index_v2）由本脚本生成，
旧格式（index.json / index.min.json）也一并按同一份元数据重新生成，
所以三种索引不会各写各的、对不上。

输入（相对于仓库根目录）：
    src/zh/*/build/keiyoushi-source-info.json   构建时由 generateSourceInfo 生成
    apk/tachiyomi-<module>-v<versionName>.apk   要发布的 APK
    icon/<packageName>.png                      插件图标
    repo.json                                   仓库信息（name / website / signingKeyFingerprint）

用法（在 main 分支的仓库根目录）：
    python scripts/gen-index.py

注意：先在 src 分支 `./gradlew :src:zh:xxx:assembleRelease` 构建，
把产物 APK 和图标复制过来，再运行本脚本。
"""

from __future__ import annotations

import gzip
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# 这个分支对外提供文件的位置（换仓库/换域名时改这里）。
BASE_URL = "https://raw.githubusercontent.com/hualiong/bilinovel-extension/main"

# ContentWarning 枚举：UNSPECIFIED=0, SAFE=1, MIXED=2, NSFW=3
SAFE = 1

# 旧格式只有 nsfw 0/1 两档，非 SAFE 一律按 1（保持和以前一样的显示）。
LEGACY_NSFW_NON_SAFE = 1


# --------------------------------------------------------------------- protobuf
# 只实现这个 schema 用得到的东西，避免为了生成一个索引去装 protobuf。
# 字段号见 mihon/data/src/main/java/mihon/data/extension/model/NetworkExtensionStore.kt


def _varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("varint 不支持负数: %d" % value)
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def _key(field: int, wire: int) -> bytes:
    return _varint(field << 3 | wire)


def _bytes(field: int, payload: bytes) -> bytes:
    return _key(field, 2) + _varint(len(payload)) + payload


def _string(field: int, value: str) -> bytes:
    return _bytes(field, value.encode("utf-8"))


def _int(field: int, value: int) -> bytes:
    return _key(field, 0) + _varint(value)


def encode_source(source: dict) -> bytes:
    out = (
        _int(1, int(source["id"]))
        + _string(2, source["name"])
        + _string(3, source["lang"])
        + _string(4, source.get("baseUrl") or "")
    )
    for mirror in source.get("mirrorUrls") or []:
        out += _string(5, mirror)
    return out


def encode_extension(info: dict) -> bytes:
    resources = _string(1, "%s/apk/%s" % (BASE_URL, info["apk"])) + _string(
        2, "%s/icon/%s.png" % (BASE_URL, info["packageName"])
    )
    out = (
        _string(1, info["name"])
        + _string(2, info["packageName"])
        + _bytes(3, resources)
        + _string(4, info["extensionLib"])
        + _int(5, int(info["versionCode"]))
        + _string(6, info["versionName"])
        + _int(7, int(info["contentWarning"]))
    )
    for source in info["sources"]:
        out += _bytes(8, encode_source(source))
    return out


def encode_store(meta: dict, extensions: list) -> bytes:
    contact = _string(1, meta["website"])
    extension_list = b"".join(_bytes(1, extension) for extension in extensions)
    return (
        _string(1, meta["name"])
        + _string(2, meta.get("shortName") or meta["name"])
        + _string(3, meta["signingKeyFingerprint"])
        + _bytes(4, contact)
        + _bytes(101, extension_list)
    )


# ------------------------------------------------------------------- 旧格式索引


def legacy_entry(info: dict) -> dict:
    langs = {source["lang"] for source in info["sources"]}
    return {
        "name": "Tachiyomi: %s" % info["name"],
        "pkg": info["packageName"],
        "apk": info["apk"],
        "lang": langs.pop() if len(langs) == 1 else "all",
        "code": int(info["versionCode"]),
        "version": info["versionName"],
        "nsfw": 0 if int(info["contentWarning"]) == SAFE else LEGACY_NSFW_NON_SAFE,
        "sources": [
            {
                "id": str(source["id"]),
                "lang": source["lang"],
                "name": source["name"],
                "baseUrl": source.get("baseUrl") or "",
            }
            for source in info["sources"]
        ],
    }


# ----------------------------------------------------------------------- 主流程


def load_extensions(root: Path) -> list:
    extensions = []
    for info_file in sorted(root.glob("src/*/*/build/keiyoushi-source-info.json")):
        info = json.loads(info_file.read_text(encoding="utf-8"))
        info["apk"] = "tachiyomi-%s-v%s.apk" % (info["module"], info["versionName"])

        apk = root / "apk" / info["apk"]
        if not apk.is_file():
            sys.exit("找不到 %s（%s 构建产物没复制过来？）" % (apk, info["module"]))

        icon = root / "icon" / ("%s.png" % info["packageName"])
        if not icon.is_file():
            sys.exit("找不到 %s，请补上插件图标" % icon)

        extensions.append(info)

    if not extensions:
        sys.exit("没有找到 src/*/*/build/keiyoushi-source-info.json，请先在 src 分支构建")

    return sorted(extensions, key=lambda info: info["packageName"])


def main() -> None:
    repo_json = ROOT / "repo.json"
    repo = json.loads(repo_json.read_text(encoding="utf-8"))
    meta = repo["meta"]

    extensions = load_extensions(ROOT)

    index_pb = gzip.compress(
        encode_store(meta, [encode_extension(info) for info in extensions]),
        mtime=0,
    )
    (ROOT / "index.pb").write_bytes(index_pb)

    entries = [legacy_entry(info) for info in extensions]
    (ROOT / "index.json").write_text(
        json.dumps(entries, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (ROOT / "index.min.json").write_text(
        json.dumps(entries, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )

    meta_fields = {key: value for key, value in repo.items() if key != "index_v2"}
    repo_json.write_text(
        json.dumps(
            {"index_v2": "%s/index.pb" % BASE_URL, **meta_fields},
            ensure_ascii=False,
            indent=4,
        ),
        encoding="utf-8",
    )

    for info in extensions:
        print(
            "%-14s %-10s code=%-7d %s"
            % (info["module"], info["versionName"], info["versionCode"], info["apk"])
        )
    print("index.pb: %d bytes -> %s/index.pb" % (len(index_pb), BASE_URL))


if __name__ == "__main__":
    main()
