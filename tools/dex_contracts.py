"""Read declared DEX interfaces without jadx's Kotlin metadata renaming.

Only class/member names, descriptors, and access flags are used. No host code is
executed. Intended for trusted local APKs, not as a general DEX validator.
"""
import re
import struct
import zipfile


def _uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
    raise ValueError("Invalid ULEB128")


def read_dex(data):
    if data[:4] != b"dex\n" or struct.unpack_from("<I", data, 40)[0] != 0x12345678:
        raise ValueError("Expected a little-endian standard DEX")
    ss, so, ts, to, ps, po, fs, fo, ms, mo, cs, co = struct.unpack_from("<12I", data, 56)
    strings = []
    for i in range(ss):
        _, offset = _uleb(data, struct.unpack_from("<I", data, so + 4 * i)[0])
        # Identifiers/descriptors here are ASCII. Other MUTF-8 strings are unused.
        strings.append(data[offset:data.index(b"\0", offset)].decode("utf-8", "replace"))
    types = [strings[struct.unpack_from("<I", data, to + 4 * i)[0]] for i in range(ts)]
    protos = []
    for i in range(ps):
        _, ret, offset = struct.unpack_from("<III", data, po + 12 * i)
        params = [] if not offset else [types[x] for x in struct.unpack_from(
            "<" + "H" * struct.unpack_from("<I", data, offset)[0], data, offset + 4)]
        protos.append("(" + "".join(params) + ")" + types[ret])
    fields, methods = [], []
    for i in range(fs):
        _, type_id, name_id = struct.unpack_from("<HHI", data, fo + 8 * i)
        fields.append((strings[name_id], types[type_id]))
    for i in range(ms):
        _, proto_id, name_id = struct.unpack_from("<HHI", data, mo + 8 * i)
        methods.append((strings[name_id], protos[proto_id]))
    index = {}
    for i in range(cs):
        cls, access, parent, _, _, _, offset, _ = struct.unpack_from("<8I", data, co + 32 * i)
        item = {"access": access,
                "super": None if parent == 0xffffffff else types[parent][1:-1].replace("/", "."),
                "fields": [], "methods": []}
        if offset:
            sizes = []
            for _ in range(4):
                count, offset = _uleb(data, offset)
                sizes.append(count)
            for group, count in enumerate(sizes):
                member_id = 0
                for _ in range(count):
                    delta, offset = _uleb(data, offset)
                    member_id += delta
                    flags, offset = _uleb(data, offset)
                    if group < 2:
                        item["fields"].append((*fields[member_id], flags))
                    else:
                        _, offset = _uleb(data, offset)  # code offset
                        item["methods"].append((*methods[member_id], flags))
        index[types[cls][1:-1].replace("/", ".")] = item
    return index


def read_apk(path):
    index = {}
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                current = read_dex(apk.read(name))
                duplicates = index.keys() & current.keys()
                if duplicates:
                    raise ValueError(f"Duplicate DEX classes: {sorted(duplicates)[:3]}")
                index.update(current)
    if not index:
        raise ValueError("No DEX classes found")
    return index


def verify_contract(index, contract):
    owner = index.get(contract["owner"])
    if owner is None:
        return "Class missing"
    if contract["kind"] == "class":
        return None
    for name, descriptor, flags in owner[contract["kind"] + "s"]:
        if name == contract["name"] and descriptor == contract["descriptor"]:
            return None if bool(flags & 8) == contract["static"] else "Static modifier changed"
    return "Member or descriptor missing"
