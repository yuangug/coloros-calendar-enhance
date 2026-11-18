"""Run real host SQLiteContentProvider batch code with in-memory fixture CRUD.

Builds a separate probe; never installs an APK or opens an installed calendar DB.
The Java compile stub is excluded from DEX, so each APK supplies its real batch code.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import uuid
import zipfile


def command(args, env=None, timeout=60):
    result = subprocess.run([str(a) for a in args], capture_output=True,
                            encoding="utf-8", errors="replace", env=env, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout + result.stderr


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ["javac", "d8", "android-jar", "classes", "module-apk", "adb", "output"]:
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", action="append", required=True, type=Path)
    args = parser.parse_args()
    out = args.output.parent.resolve()
    out.mkdir(parents=True, exist_ok=True)
    build = out / ("backend-build-" + uuid.uuid4().hex[:8])
    classes = build / "classes"
    classes.mkdir(parents=True)
    source = Path(__file__).parent / "backendcheck"
    command([args.javac, "-encoding", "UTF-8", "-cp",
             os.pathsep.join(str(p.resolve()) for p in [args.android_jar, args.classes]),
             "-d", classes, source / "SQLiteContentProvider.java", source / "BackendTransactionProbe.java"])
    jar = build / "probe-classes.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        for path in (classes / "com/lcodexs").rglob("*.class"):
            archive.write(path, path.relative_to(classes).as_posix())
        assert not any(n.startswith("com/android/") for n in archive.namelist())
    env = dict(os.environ, JAVA_HOME=str(args.javac.resolve().parents[1]))
    dex = build / "probe.jar"
    command([args.d8, "--lib", args.android_jar, "--classpath", classes,
             "--min-api", "29", "--output", dex, jar], env=env)
    adb = [args.adb, "-s", args.serial]
    remote = "/data/local/tmp/coursealarm-backend-" + uuid.uuid4().hex
    uploaded = []
    reports = []
    try:
        command(adb + ["shell", "mkdir", "-p", remote])
        for path, name in [(dex, "probe.jar"), (args.module_apk, "module.apk")]:
            command(adb + ["push", path, remote + "/" + name])
            uploaded.append(remote + "/" + name)
        for index, host in enumerate(args.apk):
            host_remote = remote + f"/host-{index}.apk"
            command(adb + ["push", host, host_remote])
            uploaded.append(host_remote)
            command(adb + ["shell", "chmod", "444"] + uploaded)
            classpath = ":".join([remote + "/probe.jar", remote + "/module.apk", host_remote])
            result = command(adb + ["shell", "su", "-c", "CLASSPATH=" + shlex.quote(classpath)
                                   + " app_process /system/bin com.lcodexs.coursealarm.BackendTransactionProbe"])
            (out / f"backend-{index}.log").write_text(result, encoding="utf-8")
            marker = "COURSEALARM_BACKEND_RESULT="
            parsed = json.loads(next(line[len(marker):] for line in result.splitlines() if line.startswith(marker)))
            reports.append(dict(host=str(host), **parsed))
            print(host.name, parsed["passed"], parsed["checks"])
    finally:
        if uploaded:
            command(adb + ["shell", "rm", "-f"] + uploaded)
        command(adb + ["shell", "rmdir", remote])
        args.output.write_text(json.dumps(reports, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if len(reports) == len(args.apk) and all(r["passed"] for r in reports) else 1


if __name__ == "__main__":
    raise SystemExit(main())
