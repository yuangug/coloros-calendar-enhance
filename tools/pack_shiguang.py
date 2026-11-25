"""Reproduce the signed-in-APK adapter snapshot from an upstream checkout (requires PyYAML)."""
import argparse
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path, PurePosixPath
from urllib.parse import urlsplit

import yaml

REVISION = "07dfb24c9334c5011ac84e326ac9c3529a8f7085"
ORIGIN = "https://github.com/ShiGuangSchedule/shiguang_warehouse"
OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/shiguang/warehouse.zip"


def pack(repo, revision, output):
    # Read one immutable tree, even with unpublished files in the supplied checkout.
    def blob(path):
        return subprocess.check_output(["git", "-C", str(repo), "show", revision + ":" + path])

    catalog, scripts = [], {}
    keys = set()
    for school in yaml.safe_load(blob("index/root_index.yaml"))["schools"]:
        folder = school["resource_folder"]
        if folder == "GLOBAL_TOOLS":  # share codes, file converters and the bridge development console
            continue
        base = "resources/" + folder + "/"
        for adapter in yaml.safe_load(blob(base + "adapters.yaml"))["adapters"]:
            path = PurePosixPath(adapter["asset_js_path"])
            url = (adapter.get("import_url") or "").strip()
            if path.is_absolute() or ".." in path.parts or path.suffix != ".js":
                raise ValueError(path)
            if url and urlsplit(url).scheme not in ("http", "https"):
                raise ValueError(url)
            source = blob(base + str(path))
            # Only timetable acquisition; skip standalone maintenance/settings utilities.
            if b"saveImportedCourses" not in source:
                continue
            sha = hashlib.sha256(source).hexdigest()
            key = (folder, adapter["adapter_id"])
            if key in keys:
                raise ValueError(key)
            keys.add(key)
            scripts[sha] = source
            catalog.append(dict(school=school["name"], initial=school["initial"], id=adapter["adapter_id"],
                                name=adapter["adapter_name"], category=adapter["category"], url=url,
                                author=adapter["maintainer"], description=adapter["description"],
                                path=base + str(path), sha=sha))
    manifest = dict(origin=ORIGIN, revision=revision, adapters=catalog)
    entries = {"catalog.json": json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode(),
               "LICENSE": blob("LICENSE")}
    entries.update({f"scripts/{key}.js": val for key, val in sorted(scripts.items())})
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w") as archive:
        for path, data in entries.items():
            info = zipfile.ZipInfo(path, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    print(f"{len(catalog)} adapters, {len(scripts)} scripts, {output.stat().st_size:,} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    parser.add_argument("--revision", default=REVISION)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    pack(args.checkout, args.revision, args.output)
