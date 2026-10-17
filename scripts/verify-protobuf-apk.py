#!/usr/bin/env python3
"""Check reflection/JNI contracts in final APK DEX bytes, independently of R8.

No Android runtime, network, decompiler, or third-party Python package is used.
Original instance fields come from the supplied MediaPipe AAR classfiles, not
from mapping.txt (which cannot describe fields R8 has removed). Entirely pruned
messages are allowed; SystemInfo and the native Java entry points are mandatory.
This structural gate is not a substitute for release-APK execution on Android.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import sys
import zipfile
import zlib


LITE_BASES = {
    "com/google/protobuf/GeneratedMessageLite",
    "com/google/protobuf/GeneratedMessageLite$ExtendableMessage",
}
REQUIRED_MESSAGE = "com.google.mediapipe.proto.MediaPipeLoggingProto$SystemInfo"
FRAMEWORK = "Lcom/google/mediapipe/framework/"
JNI_CONTRACTS = {
    FRAMEWORK + "Packet;": {"methods": {"create(J)" + FRAMEWORK + "Packet;"}},
    FRAMEWORK + "PacketListCallback;": {"methods": {"process(Ljava/util/List;)V"}},
    FRAMEWORK + "MediaPipeException;": {
        "methods": {"<init>(ILjava/lang/String;)V", "<init>(I[B)V"},
    },
    FRAMEWORK + "ProtoUtil$SerializedMessage;": {
        "fields": {"typeName": "Ljava/lang/String;", "value": "[B"},
    },
    FRAMEWORK + "PacketCreator;": {},
}


class Reader:
    def __init__(self, data: bytes, endian: str = "<", pos: int = 0):
        self.data, self.endian, self.pos = data, endian, pos

    def take(self, size: int) -> bytes:
        if size < 0 or self.pos < 0 or self.pos + size > len(self.data):
            raise ValueError("Truncated or out-of-bounds binary structure")
        result = self.data[self.pos:self.pos + size]
        self.pos += size
        return result

    def number(self, code: str) -> int:
        return struct.unpack(self.endian + code, self.take(struct.calcsize(code)))[0]

    def uleb(self) -> int:
        value = 0
        for shift in range(0, 35, 7):
            byte = self.number("B")
            if shift == 28 and byte & 0xf0:
                raise ValueError("Invalid 32-bit ULEB128")
            value |= (byte & 0x7f) << shift
            if byte < 0x80:
                return value
        raise ValueError("Unterminated ULEB128")


def index(items: list, value: int):
    if value < 0 or value >= len(items):
        raise ValueError(f"Invalid binary table index {value}/{len(items)}")
    return items[value]


def classfile(data: bytes) -> dict:
    """Read only hierarchy and declared fields from a JVM classfile."""
    r = Reader(data, ">")
    if r.number("I") != 0xcafebabe:
        raise ValueError("Invalid classfile magic")
    r.take(4)  # minor/major version
    constants: list = [None] * r.number("H")
    i = 1
    while i < len(constants):
        tag = r.number("B")
        if tag == 1:
            # Identifiers/descriptors are ASCII here; other constants may be MUTF8.
            constants[i] = r.take(r.number("H")).decode("utf-8", "replace")
        elif tag in (7, 8, 16, 19, 20):
            constants[i] = r.number("H")
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            r.take(4)
        elif tag in (5, 6):
            r.take(8)
            i += 1
        elif tag == 15:
            r.take(3)
        else:
            raise ValueError(f"Unsupported classfile constant tag {tag}")
        i += 1
    r.number("H")  # class access flags
    name = index(constants, index(constants, r.number("H")))
    super_index = r.number("H")
    parent = index(constants, index(constants, super_index)) if super_index else None
    r.take(2 * r.number("H"))  # interfaces
    fields = {}
    for _ in range(r.number("H")):
        access = r.number("H")
        field_name = index(constants, r.number("H"))
        descriptor = index(constants, r.number("H"))
        if not access & 0x0008:
            fields[field_name] = descriptor
        for _ in range(r.number("H")):
            r.number("H")
            r.take(r.number("I"))
    return {"name": name, "parent": parent, "fields": fields}


def aar_classes(paths: list[Path]) -> dict:
    classes = {}
    for path in paths:
        with zipfile.ZipFile(path) as aar:
            jars = [n for n in aar.namelist() if n == "classes.jar" or
                    (n.startswith("libs/") and n.endswith(".jar"))]
            if not jars:
                raise ValueError(f"No class JARs in {path}")
            for jar_name in jars:
                with zipfile.ZipFile(io.BytesIO(aar.read(jar_name))) as jar:
                    for name in jar.namelist():
                        if name.endswith(".class"):
                            item = classfile(jar.read(name))
                            old = classes.get(item["name"])
                            if old is not None and old != item:
                                raise ValueError(f"Conflicting AAR class {item['name']}")
                            classes[item["name"]] = item
    return classes


def lite_messages(classes: dict) -> dict:
    messages = {}
    for name, item in classes.items():
        parent, seen = item["parent"], {name}
        while parent is not None:
            if parent in LITE_BASES:
                messages[name.replace("/", ".")] = item
                break
            if parent in seen:
                raise ValueError(f"Cyclic class hierarchy at {name}")
            seen.add(parent)
            parent = classes.get(parent, {}).get("parent")
    return messages


def dex_classes(data: bytes) -> dict:
    """Read defined fields/methods, never merely referenced field_ids/method_ids."""
    if len(data) < 112 or not re.fullmatch(rb"dex\n0(?:35|37|38|39|40)\x00", data[:8]):
        raise ValueError("Expected supported standard DEX 035/037/038/039/040")
    header = struct.unpack_from("<20I", data, 32)
    if header[0] != len(data) or header[1] != 112 or header[2] != 0x12345678:
        raise ValueError("Invalid DEX size, header, or endian tag")
    if hashlib.sha1(data[32:]).digest() != data[12:32]:
        raise ValueError("DEX SHA-1 signature mismatch")
    if zlib.adler32(data[12:]) & 0xffffffff != struct.unpack_from("<I", data, 8)[0]:
        raise ValueError("DEX Adler-32 checksum mismatch")

    def table(count: int, offset: int, width: int) -> Reader:
        r = Reader(data, pos=offset)
        r.take(count * width)
        r.pos = offset
        return r

    strings = []
    r = table(header[6], header[7], 4)
    for _ in range(header[6]):
        s = Reader(data, pos=r.number("I"))
        s.uleb()  # UTF-16 length; MUTF8 NUL is encoded and cannot terminate early.
        end = data.find(b"\x00", s.pos)
        if end < 0:
            raise ValueError("Unterminated DEX string")
        strings.append(data[s.pos:end].decode("utf-8", "replace"))
    r = table(header[8], header[9], 4)
    types = [index(strings, r.number("I")) for _ in range(header[8])]

    def type_list(offset: int) -> list[str]:
        if not offset:
            return []
        p = Reader(data, pos=offset)
        count = p.number("I")
        if count > (len(data) - p.pos) // 2:
            raise ValueError("Truncated DEX type list")
        return [index(types, p.number("H")) for _ in range(count)]

    protos = []
    r = table(header[10], header[11], 12)
    for _ in range(header[10]):
        r.number("I")  # shorty
        result = index(types, r.number("I"))
        parameters_offset = r.number("I")
        parameters = "".join(type_list(parameters_offset))
        protos.append(f"({parameters}){result}")
    fields = []
    r = table(header[12], header[13], 8)
    for _ in range(header[12]):
        owner, descriptor = index(types, r.number("H")), index(types, r.number("H"))
        fields.append((owner, index(strings, r.number("I")), descriptor))
    methods = []
    r = table(header[14], header[15], 8)
    for _ in range(header[14]):
        owner, descriptor = index(types, r.number("H")), index(protos, r.number("H"))
        methods.append((owner, index(strings, r.number("I")) + descriptor))
    classes = {}
    r = table(header[16], header[17], 32)
    for _ in range(header[16]):
        definition = struct.unpack("<8I", r.take(32))
        name = index(types, definition[0])
        if name in classes:
            raise ValueError(f"Duplicate DEX class {name}")
        item = {"fields": {}, "methods": set(), "method_access": {},
                "access": definition[1],
                "parent": None if definition[2] == 0xffffffff else index(types, definition[2]),
                "interfaces": type_list(definition[3])}
        if definition[6]:
            c = Reader(data, pos=definition[6])
            static_count, instance_count, direct_count, virtual_count = [c.uleb() for _ in range(4)]
            for count, is_static in ((static_count, True), (instance_count, False)):
                field_index = 0
                for position in range(count):
                    diff = c.uleb()
                    if position and not diff:
                        raise ValueError("Duplicate encoded DEX field")
                    field_index += diff
                    access = c.uleb()
                    owner, field_name, descriptor = index(fields, field_index)
                    if owner != name or bool(access & 0x0008) != is_static:
                        raise ValueError("DEX field owner/static flag mismatch")
                    if not is_static:
                        item["fields"][field_name] = descriptor
            for count in (direct_count, virtual_count):
                method_index = 0
                for position in range(count):
                    diff = c.uleb()
                    if position and not diff:
                        raise ValueError("Duplicate encoded DEX method")
                    method_index += diff
                    access = c.uleb()
                    c.uleb()  # code offset (zero for abstract/interface methods)
                    owner, signature = index(methods, method_index)
                    if owner != name:
                        raise ValueError("DEX method owner mismatch")
                    item["methods"].add(signature)
                    item["method_access"][signature] = access
        classes[name] = item
    return classes


def apk_classes(path: Path) -> tuple[dict, list[str]]:
    classes = {}
    with zipfile.ZipFile(path) as apk:
        names = sorted(n for n in apk.namelist() if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", n))
        if not names or "classes.dex" not in names or len(names) != len(set(names)):
            raise ValueError("APK lacks classes.dex or contains duplicate DEX entries")
        for name in names:
            for descriptor, item in dex_classes(apk.read(name)).items():
                if descriptor in classes:
                    raise ValueError(f"Class defined in multiple DEX files: {descriptor}")
                classes[descriptor] = item
    return classes, names


def class_mapping(path: Path) -> dict[str, str]:
    result = {}
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            match = re.fullmatch(r"(\S+) -> (\S+):\s*", line)
            if match:
                original, renamed = match.groups()
                if original in result and result[original] != renamed:
                    raise ValueError(f"Conflicting class mapping for {original}")
                result[original] = renamed
    if not result:
        raise ValueError("No class entries in R8 mapping")
    return result


def map_type(descriptor: str, mapping: dict[str, str]) -> str:
    def rename(match: re.Match) -> str:
        original = match.group(1).replace("/", ".")
        return "L" + mapping.get(original, original).replace(".", "/") + ";"
    return re.sub(r"L([^;]+);", rename, descriptor)


def inspect_contracts(messages: dict, mapping: dict, classes: dict) -> dict:
    results, pruned, failures = [], [], []
    if REQUIRED_MESSAGE not in messages:
        failures.append(f"Required source message absent from AAR inputs: {REQUIRED_MESSAGE}")
    for original, source in sorted(messages.items()):
        descriptor = "L" + mapping.get(original, original).replace(".", "/") + ";"
        actual = classes.get(descriptor)
        if actual is None and original not in mapping and original != REQUIRED_MESSAGE:
            pruned.append(original)
            continue
        expected = {name: map_type(kind, mapping) for name, kind in source["fields"].items()}
        fields = actual["fields"] if actual is not None else {}
        missing = sorted(name for name in expected if name not in fields)
        mismatches = {name: {"expected": kind, "actual": fields[name]}
                      for name, kind in expected.items() if name in fields and fields[name] != kind}
        passed = actual is not None and not missing and not mismatches
        results.append({"original_class": original, "dex_descriptor": descriptor,
                        "status": "PASS" if passed else "FAIL", "class_present": actual is not None,
                        "expected_instance_fields": expected, "actual_instance_fields": fields,
                        "missing_fields": missing, "type_mismatches": mismatches})
        if not passed:
            failures.append(f"Protobuf instance-field contract broken: {original}")
    native = []
    for descriptor, contract in JNI_CONTRACTS.items():
        actual = classes.get(descriptor)
        fields = actual["fields"] if actual is not None else {}
        methods = actual["methods"] if actual is not None else set()
        missing_methods = sorted(contract.get("methods", set()) - methods)
        invalid_fields = {name: {"expected": kind, "actual": fields.get(name)}
                          for name, kind in contract.get("fields", {}).items() if fields.get(name) != kind}
        passed = actual is not None and not missing_methods and not invalid_fields
        native.append({"dex_descriptor": descriptor, "status": "PASS" if passed else "FAIL",
                       "class_present": actual is not None, "missing_methods": missing_methods,
                       "invalid_fields": invalid_fields})
        if not passed:
            failures.append(f"Native Java contract broken: {descriptor}")
    callbacks = callback_implementations(classes)
    if not callbacks or any(item["status"] != "PASS" for item in callbacks):
        failures.append("Missing or invalid concrete PacketListCallback.process implementation")
    return {"status": "FAIL" if failures else "PASS", "failures": failures,
            "source_message_count": len(messages), "checked_message_count": len(results),
            "checked_instance_field_count": sum(len(r["expected_instance_fields"]) for r in results),
            "pruned_message_count": len(pruned), "pruned_messages": pruned,
            "message_checks": results, "native_java_checks": native,
            "packet_list_callback_implementations": callbacks}


def callback_implementations(classes: dict) -> list[dict]:
    callback = FRAMEWORK + "PacketListCallback;"
    signature = "process(Ljava/util/List;)V"

    def implements(name: str, seen: set) -> bool:
        if name == callback:
            return True
        if name in seen or name not in classes:
            return False
        item = classes[name]
        ancestors = item["interfaces"] + ([item["parent"]] if item["parent"] else [])
        return any(implements(parent, seen | {name}) for parent in ancestors)

    result = []
    for name, item in sorted(classes.items()):
        if item["access"] & (0x0200 | 0x0400) or not implements(name, set()):
            continue  # interface or abstract class
        current, seen, owner = name, set(), None
        while current in classes and current not in seen:
            seen.add(current)
            candidate = classes[current]
            if signature in candidate["method_access"]:
                # JNI calls the instance method; a static/abstract/private method is invalid.
                access = candidate["method_access"][signature]
                if not access & (0x0002 | 0x0008 | 0x0400):
                    owner = current
                break
            current = candidate["parent"]
        result.append({"dex_descriptor": name, "method_owner": owner,
                       "method": signature, "status": "PASS" if owner else "FAIL"})
    return result


def file_record(path: Path) -> dict:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return {"path": str(path.resolve()), "size_bytes": path.stat().st_size, "sha256": digest.hexdigest()}


def self_test() -> None:
    """Deterministic parser/contract regression fixtures; no compiled artifacts needed."""
    def expect_error(action) -> None:
        try:
            action()
        except ValueError:
            return
        raise AssertionError("Malformed input was accepted")

    # Both field IDs exist, but only field_ is actually defined on the class.
    strings = ["I", "Lexample/Message;", "field_", "unused_"]
    string_offset, type_offset, field_offset, class_offset, data_offset = 112, 128, 136, 152, 184
    payload, offsets = bytearray(), []
    for value in strings:
        offsets.append(data_offset + len(payload))
        payload.extend(bytes([len(value)]) + value.encode() + b"\x00")
    class_data_offset = data_offset + len(payload)
    payload.extend(b"\x00\x01\x00\x00\x00\x02")
    dex = bytearray(112)
    dex.extend(struct.pack("<4I", *offsets))
    dex.extend(struct.pack("<2I", 0, 1))
    dex.extend(struct.pack("<HHIHHI", 1, 0, 2, 1, 0, 3))
    dex.extend(struct.pack("<8I", 1, 1, 0xffffffff, 0, 0xffffffff, 0, class_data_offset, 0))
    dex.extend(payload)
    dex[:8] = b"dex\n039\x00"
    struct.pack_into("<20I", dex, 32, len(dex), 112, 0x12345678, 0, 0, 0,
                     4, string_offset, 2, type_offset, 0, 0, 2, field_offset, 0, 0,
                     1, class_offset, len(payload), data_offset)
    dex[12:32] = hashlib.sha1(dex[32:]).digest()
    struct.pack_into("<I", dex, 8, zlib.adler32(dex[12:]) & 0xffffffff)
    assert dex_classes(bytes(dex))["Lexample/Message;"]["fields"] == {"field_": "I"}
    expect_error(lambda: dex_classes(bytes(dex[:-1])))
    dex[-1] ^= 1
    expect_error(lambda: dex_classes(bytes(dex)))
    expect_error(lambda: Reader(b"\xff\xff\xff\xff\x10").uleb())

    # Minimal original classfile, including one static field to exclude.
    cp = bytearray()
    for value in ("example/Message", "com/google/protobuf/GeneratedMessageLite", "field_", "I", "STATIC"):
        encoded = value.encode()
        cp.extend(b"\x01" + struct.pack(">H", len(encoded)) + encoded)
    cp.extend(b"\x07\x00\x01\x07\x00\x02")
    original = (struct.pack(">IHHH", 0xcafebabe, 0, 52, 8) + cp +
                struct.pack(">HHHHH", 1, 6, 7, 0, 2) +
                struct.pack(">HHHHHHHH", 2, 3, 4, 0, 8, 5, 4, 0))
    parsed = classfile(bytes(original))
    assert parsed["fields"] == {"field_": "I"} and parsed["parent"] in LITE_BASES
    descendant = {"name": "example/Child", "parent": parsed["name"], "fields": {}}
    assert len(lite_messages({parsed["name"]: parsed, descendant["name"]: descendant})) == 2
    assert map_type("[[Lexample/Message;", {"example.Message": "a.B"}) == "[[La/B;"

    # A positive control, plus missing/renamed/wrong-type field negative controls.
    classes = {}
    for name, contract in JNI_CONTRACTS.items():
        methods = contract.get("methods", set())
        classes[name] = {"fields": dict(contract.get("fields", {})), "methods": methods,
                         "method_access": {method: 1 for method in methods}, "access": 0x0400,
                         "parent": None, "interfaces": []}
    callback = FRAMEWORK + "PacketListCallback;"
    classes["LCallback;"] = {"fields": {}, "methods": {"process(Ljava/util/List;)V"},
                            "method_access": {"process(Ljava/util/List;)V": 1}, "access": 1,
                            "parent": None, "interfaces": [callback]}
    classes["LMessage;"] = {"fields": {"platform_": "I"}, "methods": set(),
                           "method_access": {}, "access": 1, "parent": None, "interfaces": []}
    messages = {REQUIRED_MESSAGE: {"fields": {"platform_": "I"}}, "example.Pruned": {"fields": {"unused_": "I"}}}
    mapping = {REQUIRED_MESSAGE: "Message"}
    assert inspect_contracts(messages, mapping, classes)["status"] == "PASS"
    for fields in ({}, {"renamed": "I"}, {"platform_": "J"}):
        classes["LMessage;"]["fields"] = fields
        assert inspect_contracts(messages, mapping, classes)["status"] == "FAIL"
    classes["LMessage;"]["fields"] = {"platform_": "I"}
    classes["LCallback;"]["method_access"] = {"renamed(Ljava/util/List;)V": 1}
    assert inspect_contracts(messages, mapping, classes)["status"] == "FAIL"
    print("PASS 12 Protobuf/JNI gate self-tests (binary definitions, integrity, inheritance, reflection, callback)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="Run deterministic parser/contract regression tests")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--mapping", type=Path, help="R8 mapping from the exact APK build")
    parser.add_argument("--aar", type=Path, action="append", help="MediaPipe AAR; repeat for core and vision")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if not all((args.apk, args.mapping, args.aar, args.report)):
        parser.error("--apk, --mapping, --aar (repeatable), and --report are required")
    report = {"schema_version": 1, "android_runtime": "NOT_RUN",
              "scope": "Actual APK DEX reflection fields and fixed MediaPipe native Java entry points",
              "limitations": ["Mapping must belong to this APK; input hashes are recorded for provenance.",
                              "Structural checks do not execute MediaPipe, models, JNI, or Android playback."]}
    try:
        report["inputs"] = {"apk": file_record(args.apk), "mapping": file_record(args.mapping),
                            "aars": [file_record(path) for path in args.aar]}
        classes, dex_names = apk_classes(args.apk)
        report["dex_files"] = dex_names
        report.update(inspect_contracts(lite_messages(aar_classes(args.aar)), class_mapping(args.mapping), classes))
    except (OSError, ValueError, KeyError, TypeError, struct.error, zipfile.BadZipFile) as error:
        report.update(status="FAIL", failures=[f"Inspection error: {error}"])
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{report['status']} Protobuf/JNI APK gate: {report.get('checked_message_count', 0)} messages, "
          f"{report.get('checked_instance_field_count', 0)} instance fields, report={args.report}")
    for failure in report.get("failures", [])[:8]:
        print(f"  {failure}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
