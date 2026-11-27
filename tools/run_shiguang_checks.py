"""Offline adapter/bridge/conversion checks. JSON Java is a test dependency, never packaged."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--json-jar", type=Path, required=True, help="org.json:json:20240303")
    parser.add_argument("--apk", type=Path, required=True, help="Built module APK, only read")
    parser.add_argument("--xit-capture", type=Path, help="Optional offline browser replay output")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    assets = root / "app/src/main/assets/shiguang"
    code = root / "app/src/main/java/com/lcodexs/coursealarm"
    tests = root / "tools/shiguangcheck"
    suffix = ".exe" if (args.jdk / "bin/javac.exe").exists() else ""
    build = root / "build"
    build.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="shiguang-checks-", dir=build) as directory:
        work = Path(directory)
        with zipfile.ZipFile(assets / "warehouse.zip") as archive:
            catalog = json.loads(archive.read("catalog.json"))
            assert catalog["revision"] == "07dfb24c9334c5011ac84e326ac9c3529a8f7085"
            assert "Permission is hereby granted" in archive.read("LICENSE").decode()
            scripts = {}
            for adapter in catalog["adapters"]:
                data = archive.read("scripts/" + adapter["sha"] + ".js")
                assert hashlib.sha256(data).hexdigest() == adapter["sha"]
                scripts[adapter["sha"]] = data.decode()
                if adapter["id"] == "zhengfang_01":
                    (work / "zhengfang.js").write_bytes(data)
            (work / "scripts.json").write_text(json.dumps(list(scripts.values())), encoding="utf-8")
        subprocess.run(["node", "-e", "const vm=require('node:vm');const s=require(process.argv[1]);"
                        "for(const code of s)new vm.Script(code);console.log('PASS: '+s.length+' upstream script syntax checks')",
                        str(work / "scripts.json")], check=True, timeout=30)
        subprocess.run(["node", str(tests / "bridge.cjs"), str(assets / "bridge.js"), str(work / "zhengfang.js")],
                       check=True, timeout=30)
        subprocess.run([str(args.jdk / "bin" / ("javac" + suffix)), "-encoding", "UTF-8", "-cp", str(args.json_jar),
                        "-d", str(work), *[str(code / (name + ".java")) for name in ("Course", "ShiguangCatalog", "ShiguangCourses", "JwParser", "XitTiming")],
                        str(tests / "CourseConversionCheck.java"),str(tests / "XitCheck.java")], check=True, timeout=30)
        subprocess.run([str(args.jdk / "bin" / ("java" + suffix)), "-cp", os.pathsep.join((str(work), str(args.json_jar.resolve()))),
                        "com.lcodexs.coursealarm.CourseConversionCheck", str(args.apk.resolve())], check=True, timeout=60)
        subprocess.run([str(args.jdk / "bin" / ("java" + suffix)), "-cp", os.pathsep.join((str(work), str(args.json_jar.resolve()))),
                        "com.lcodexs.coursealarm.XitCheck", *([str(args.xit_capture.resolve())] if args.xit_capture else [])],check=True,timeout=30)


if __name__ == "__main__":
    main()
