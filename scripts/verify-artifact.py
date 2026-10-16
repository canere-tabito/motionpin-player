#!/usr/bin/env python3
"""Inspect final APK bytes, without installing, signing, or modifying the APK.

Default mode is a distribution gate, including an independently supplied canonical
certificate fingerprint. --mode build permits inspection of an unsigned local
build, but always reports release_ready=false. Device tests are outside this tool.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import zipfile

ANDROID_NS = "http://schemas.android.com/apk/res/android"
PAGE_SIZE = 16384


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fingerprint(value: str) -> str:
    normalized = re.sub(r"[:\s]", "", value).lower()
    if not re.fullmatch(r"[0-9a-f]{64}", normalized):
        raise ValueError("SHA-256 must contain exactly 64 hexadecimal digits")
    return normalized


def strings_in_pool(data: bytes, start: int, size: int) -> list[str]:
    header = struct.unpack_from("<H", data, start + 2)[0]
    count, _, flags, strings_start, _ = struct.unpack_from("<IIIII", data, start + 8)
    if header < 28 or start + size > len(data):
        raise ValueError("Invalid binary XML string pool")
    offsets = struct.unpack_from(f"<{count}I", data, start + header)
    result = []

    def length8(pos: int) -> tuple[int, int]:
        first = data[pos]
        return (((first & 0x7f) << 8) | data[pos + 1], pos + 2) if first & 0x80 else (first, pos + 1)

    def length16(pos: int) -> tuple[int, int]:
        first = struct.unpack_from("<H", data, pos)[0]
        return (((first & 0x7fff) << 16) | struct.unpack_from("<H", data, pos + 2)[0], pos + 4) if first & 0x8000 else (first, pos + 2)

    for offset in offsets:
        pos = start + strings_start + offset
        if flags & 0x100:
            _, pos = length8(pos)
            length, pos = length8(pos)
            end = pos + length
            encoding = "utf-8"
        else:
            length, pos = length16(pos)
            end = pos + length * 2
            encoding = "utf-16-le"
        if end >= start + size:
            raise ValueError("Binary XML string exceeds its pool")
        result.append(data[pos:end].decode(encoding))
    return result


def manifest_elements(data: bytes) -> list[tuple[str, dict[str, object]]]:
    if len(data) < 8:
        raise ValueError("Truncated Android manifest")
    kind, header_size, size = struct.unpack_from("<HHI", data)
    if kind != 3 or size != len(data) or header_size < 8:
        raise ValueError("Expected compiled Android binary XML")
    strings: list[str] = []
    elements = []
    cursor = header_size
    while cursor < size:
        kind, header, chunk_size = struct.unpack_from("<HHI", data, cursor)
        if header < 8 or chunk_size < header or cursor + chunk_size > size:
            raise ValueError("Invalid binary XML chunk")
        if kind == 1:
            strings = strings_in_pool(data, cursor, chunk_size)
        elif kind == 0x0102:
            if header != 16 or chunk_size < 36:
                raise ValueError("Invalid manifest start element")
            _, name, attr_start, attr_size, attr_count = struct.unpack_from("<IIHHH", data, cursor + header)
            if attr_size < 20:
                raise ValueError("Invalid manifest attribute width")
            attrs: dict[str, object] = {}
            for i in range(attr_count):
                pos = cursor + header + attr_start + i * attr_size
                if pos + 20 > cursor + chunk_size:
                    raise ValueError("Manifest attribute exceeds chunk")
                ns, attr_name, raw, typed_size, _, data_type, value = struct.unpack_from("<IIIHBBI", data, pos)
                if typed_size != 8:
                    raise ValueError("Invalid typed manifest value")
                key = strings[attr_name]
                if ns != 0xffffffff:
                    key = f"{{{strings[ns]}}}{key}"
                if raw != 0xffffffff:
                    decoded: object = strings[raw]
                elif data_type == 3:
                    decoded = strings[value]
                elif data_type == 0x12:
                    decoded = value != 0
                elif data_type in (0x10, 0x11):
                    decoded = value
                else:
                    decoded = {"type": data_type, "value": value}
                if key in attrs:
                    raise ValueError("Duplicate manifest attribute")
                attrs[key] = decoded
            elements.append((strings[name], attrs))
        cursor += chunk_size
    return elements


def elf_load_segments(data: bytes) -> list[dict[str, int]]:
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected 64-bit little-endian ELF")
    elf_type, machine = struct.unpack_from("<HH", data, 16)
    if elf_type != 3 or machine != 183:
        raise ValueError("Expected arm64 ET_DYN shared library")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    if phentsize < 56 or not phnum or phoff + phentsize * phnum > len(data):
        raise ValueError("Invalid ELF program header table")
    loads = []
    for index in range(phnum):
        segment = struct.unpack_from("<IIQQQQQQ", data, phoff + index * phentsize)
        if segment[0] == 1:
            _, _, offset, vaddr, _, file_size, memory_size, alignment = segment
            if offset + file_size > len(data) or file_size > memory_size:
                raise ValueError("Invalid PT_LOAD byte range")
            if alignment < PAGE_SIZE or alignment & (alignment - 1):
                raise ValueError(f"PT_LOAD p_align {alignment} does not support 16KB pages")
            if offset % PAGE_SIZE != vaddr % PAGE_SIZE:
                raise ValueError("PT_LOAD offset/vaddr are not congruent modulo 16KB")
            loads.append({"offset": offset, "virtual_address": vaddr, "alignment": alignment})
    if not loads:
        raise ValueError("ELF contains no PT_LOAD segments")
    return loads


def zip_data_offset(apk: Path, info: zipfile.ZipInfo) -> int:
    with apk.open("rb") as stream:
        stream.seek(info.header_offset)
        header = stream.read(30)
    if len(header) != 30 or struct.unpack_from("<I", header)[0] != 0x04034b50:
        raise ValueError("Invalid ZIP local file header")
    name_size, extra_size = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_size + extra_size


def sdk_tool(name: str, sdk: Path | None) -> str | None:
    found = shutil.which(name)
    if found:
        return found
    if sdk:
        candidates = sorted((sdk / "build-tools").glob(f"*/{name}"), reverse=True)
        return str(candidates[0]) if candidates else None
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--mode", choices=("release", "build"), default="release")
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME"))
    parser.add_argument("--expected-certificate-sha256", help="Canonical app signing certificate, obtained independently")
    parser.add_argument("--model-file", action="append", default=[], metavar="APK_PATH=SHA256")
    parser.add_argument("--model-manifest", type=Path, help="MODEL_MANIFEST.json with artifacts: [{path, sha256}]")
    parser.add_argument("--application-id", default="com.caneretabito.motionpin")
    parser.add_argument("--native-library", default="lib/arm64-v8a/libmotionpin_rife.so")
    parser.add_argument("--report", type=Path, help="Also save this JSON report")
    args = parser.parse_args()
    checks: list[dict[str, object]] = []
    report: dict[str, object] = {"apk": str(args.apk.resolve()), "mode": args.mode, "release_ready": False, "checks": checks}

    def record(name: str, status: str, detail: object) -> None:
        checks.append({"check": name, "status": status, "detail": detail})

    def check(name: str, condition: bool, detail: object) -> None:
        record(name, "PASS" if condition else "FAIL", detail)

    try:
        report["size_bytes"] = args.apk.stat().st_size
        with args.apk.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        report["sha256"] = digest
        with zipfile.ZipFile(args.apk) as archive:
            names = archive.namelist()
            check("unique_zip_entries", len(names) == len(set(names)), "No duplicate APK entry names")
            corrupted = archive.testzip()
            check("zip_integrity", corrupted is None, {"first_bad_entry": corrupted})
            elements = manifest_elements(archive.read("AndroidManifest.xml"))
            roots = [attrs for tag, attrs in elements if tag == "manifest"]
            apps = [attrs for tag, attrs in elements if tag == "application"]
            sdks = [attrs for tag, attrs in elements if tag == "uses-sdk"]
            if len(roots) != 1 or len(apps) != 1 or len(sdks) != 1:
                raise ValueError("Manifest must have one manifest, application, and uses-sdk")
            android = lambda name: f"{{{ANDROID_NS}}}{name}"
            permissions = sorted(str(attrs.get(android("name"))) for tag, attrs in elements if tag.startswith("uses-permission"))
            manifest = {"application_id": roots[0].get("package"), "version_code": roots[0].get(android("versionCode")), "version_name": roots[0].get(android("versionName")), "min_sdk": sdks[0].get(android("minSdkVersion")), "target_sdk": sdks[0].get(android("targetSdkVersion")), "debuggable": apps[0].get(android("debuggable"), False), "allow_backup": apps[0].get(android("allowBackup"), True), "permissions": permissions}
            report["manifest"] = manifest
            check("application_id", manifest["application_id"] == args.application_id, manifest["application_id"])
            check("sdk_versions", manifest["min_sdk"] == 29 and manifest["target_sdk"] == 35, {"min": manifest["min_sdk"], "target": manifest["target_sdk"]})
            check("release_not_debuggable", manifest["debuggable"] is False, manifest["debuggable"])
            check("no_internet_permission", "android.permission.INTERNET" not in permissions, permissions)
            check("automatic_backup_disabled", manifest["allow_backup"] is False, manifest["allow_backup"])
            natives = [name for name in names if name.endswith(".so")]
            check("required_native_library", args.native_library in natives, args.native_library)
            check("arm64_only", bool(natives) and all(name.startswith("lib/arm64-v8a/") for name in natives), natives)
            for name in natives:
                try:
                    info = archive.getinfo(name)
                    offset = zip_data_offset(args.apk, info)
                    check(f"native_zip_alignment:{name}", info.compress_type == zipfile.ZIP_STORED and offset % PAGE_SIZE == 0, {"data_offset": offset, "compression": info.compress_type})
                    loads = elf_load_segments(archive.read(name))
                    record(f"native_elf_alignment:{name}", "PASS", loads)
                except (ValueError, struct.error) as error:
                    record(f"native_elf_alignment:{name}", "FAIL", str(error))
            expected_models = []
            if args.model_manifest:
                model_manifest = json.loads(args.model_manifest.read_text())
                expected_models.extend({"apk_path": item["path"], "sha256": item["sha256"]} for item in model_manifest["artifacts"])
                report["model_manifest_sha256"] = sha256(args.model_manifest.read_bytes())
                packaged_manifest = "assets/MODEL_MANIFEST.json"
                check("packaged_model_manifest", packaged_manifest in names and archive.read(packaged_manifest) == args.model_manifest.read_bytes(), packaged_manifest)
            check("packaged_third_party_notices", "assets/THIRD_PARTY_NOTICES.txt" in names and archive.getinfo("assets/THIRD_PARTY_NOTICES.txt").file_size > 0, "assets/THIRD_PARTY_NOTICES.txt")
            for model in args.model_file:
                name, expected = model.rsplit("=", 1)
                expected_models.append({"apk_path": name, "sha256": expected})
            check("model_hash_contract_provided", bool(expected_models), "Expected model bytes must be specified explicitly")
            for model in expected_models:
                name, expected = model["apk_path"], fingerprint(model["sha256"])
                actual = sha256(archive.read(name)) if name in names else None
                check(f"model_asset:{name}", actual == expected, {"expected_sha256": expected, "actual_sha256": actual})
    except (OSError, ValueError, KeyError, IndexError, struct.error, zipfile.BadZipFile) as error:
        record("apk_inspection", "FAIL", str(error))

    zipalign = sdk_tool("zipalign", args.sdk)
    if zipalign:
        result = subprocess.run([zipalign, "-c", "-P", "16", "-v", "4", str(args.apk)], capture_output=True, text=True)
        check("sdk_zipalign_16kb", result.returncode == 0, {"tool": zipalign, "exit_code": result.returncode, "output_tail": (result.stdout + result.stderr)[-3000:]})
    else:
        record("sdk_zipalign_16kb", "NOT_RUN", "Install Android SDK build-tools or supply --sdk")

    apksigner = sdk_tool("apksigner", args.sdk)
    certificate_matches = False
    if apksigner:
        result = subprocess.run([apksigner, "verify", "--verbose", "--print-certs", str(args.apk)], capture_output=True, text=True)
        output = result.stdout + result.stderr
        certs = [fingerprint(value) for value in re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", output)]
        report["signing_certificates_sha256"] = certs
        if result.returncode == 0:
            check("apk_signature", True, {"tool": apksigner, "output": output.strip()})
            check("not_android_debug_certificate", "CN=Android Debug" not in output, "Known Android debug subject must not sign distribution artifacts")
            if args.expected_certificate_sha256:
                try:
                    expected_cert = fingerprint(args.expected_certificate_sha256)
                    certificate_matches = certs == [expected_cert]
                    check("canonical_signing_certificate", certificate_matches, {"expected": expected_cert, "actual": certs})
                except ValueError as error:
                    record("canonical_signing_certificate", "FAIL", str(error))
            else:
                record("canonical_signing_certificate", "NOT_RUN", "Supply the independently obtained canonical certificate SHA-256")
        else:
            record("apk_signature", "NOT_RUN" if args.mode == "build" else "FAIL", {"exit_code": result.returncode, "detail": "Unsigned or invalidly signed APK; local build only, not distributable", "output": output.strip()})
    else:
        record("apk_signature", "NOT_RUN", "Install Android SDK build-tools or supply --sdk; signing is unverified")

    failed = any(check["status"] == "FAIL" for check in checks)
    required_checks = [check for check in checks if args.mode == "release" or check["check"] not in ("apk_signature", "canonical_signing_certificate", "not_android_debug_certificate")]
    incomplete = any(check["status"] == "NOT_RUN" for check in required_checks)
    report["status"] = "FAIL" if failed else "NOT_RUN" if incomplete else "PASS"
    report["release_ready"] = args.mode == "release" and report["status"] == "PASS" and certificate_matches
    report["distribution_status"] = "ARTIFACT_GATES_PASS_DEVICE_AND_LICENSE_REVIEW_STILL_REQUIRED" if report["release_ready"] else "NOT_FOR_DISTRIBUTION"
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered)
    sys.stdout.write(rendered)
    return 1 if failed else 2 if incomplete else 0


if __name__ == "__main__":
    raise SystemExit(main())
