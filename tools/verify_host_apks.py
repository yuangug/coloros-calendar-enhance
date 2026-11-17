"""Check local calendar APK contracts and optionally run an isolated ART probe.

Requires Python 3.10+, Android aapt2, and (only for --probe) adb + rooted target.
Never installs a host APK, launches its application, or opens a calendar provider.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import uuid

from dex_contracts import read_apk, verify_contract


def command(args, timeout=60):
    result = subprocess.run([str(a) for a in args], capture_output=True,
                            encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {result.stdout[-2000:]} {result.stderr[-2000:]}")
    return result.stdout


def selectors(index):
    def choose(owner, descriptor, names=None, static=False):
        candidates = [m for m in index[owner]["methods"]
                      if m[1] == descriptor and bool(m[2] & 8) == static
                      and (names is None or m[0] in names)]
        if len(candidates) != 1:
            raise ValueError(f"Ambiguous or missing entry: {owner}, {candidates}")
        return owner + "#" + candidates[0][0] + descriptor

    fold = "com.android.calendar.ui.main.calendar.view.DateTabFoldLayout"
    helper = "com.android.calendar.ui.main.hepler.ImportCourseTableHelper"
    activity = "com.android.calendar.card.schooltimetable.SchoolTimeTableActivity"
    adapter = next(f[1][1:-1].replace("/", ".") for f in index[activity]["fields"] if f[0] == "weekAdapter")
    base = adapter
    while base in index and base != "androidx.recyclerview.widget.RecyclerView$Adapter":
        base = index[base]["super"]
    if base != "androidx.recyclerview.widget.RecyclerView$Adapter":
        raise ValueError("weekAdapter is not a RecyclerView.Adapter")
    return {
        "changeType": choose(fold, "(I)V", ["changeType", "s", "t"]),
        "createSheet": choose(helper, "(Landroidx/fragment/app/FragmentActivity;Z)V", static=True),
        "weekAdapter": choose(adapter, "(Landroid/content/Context;Ljava/lang/String;Ljava/util/List;IZI)V", ["<init>"]),
        "pagerCurrentItem": choose("androidx.viewpager2.widget.ViewPager2", "(IZ)V", ["setCurrentItem", "m"]),
    }


def static_check(apk, spec, aapt2):
    index = read_apk(apk)
    errors = []
    for contract in spec["contracts"]:
        reason = verify_contract(index, contract)
        if reason:
            errors.append({"contract": contract, "reason": reason})
    entries = {}
    try:
        entries = selectors(index)
    except (KeyError, ValueError, StopIteration) as error:
        errors.append({"selectors": str(error)})
    badging = command([aapt2, "dump", "badging", apk])
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package or package[1] != "com.coloros.calendar":
        raise ValueError("Expected com.coloros.calendar APK")
    resource_dump = command([aapt2, "dump", "resources", apk])
    resources = set(re.findall(r"resource 0x[0-9a-f]+ ([\w.]+/[\w.]+)", resource_dump))
    for item in spec["resources"]:
        name = item["type"] + "/" + item["name"]
        if name not in resources:
            errors.append({"resource": name, "reason": "Missing resource"})
    return {"apk": str(apk.resolve()), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "package": package[1], "versionCode": int(package[2]), "versionName": package[3],
            "classes": len(index), "contractCount": len(spec["contracts"]),
            "resourceCount": len(spec["resources"]), "selectors": entries,
            "passed": not errors, "errors": errors}


def runtime_check(args, reports):
    adb = [args.adb, "-s", args.serial]
    if command(adb + ["get-state"]).strip() != "device":
        raise RuntimeError("Requested device is not online")
    if command(adb + ["shell", "su", "-c", "id -u"]).strip() != "0":
        raise RuntimeError("Probe requires a rooted target")
    remote = "/data/local/tmp/coursealarm-compat-" + uuid.uuid4().hex
    command(adb + ["shell", "mkdir", "-p", remote])
    command(adb + ["push", args.probe, remote + "/probe.apk"])
    command(adb + ["push", args.contracts, remote + "/contracts.json"])
    device = command(adb + ["shell", "getprop", "ro.build.fingerprint"]).strip()
    for i, report in enumerate(reports):
        host = remote + f"/host-{i}.apk"
        command(adb + ["push", report["apk"], host])
        command(adb + ["shell", "chmod", "444", host, remote + "/probe.apk"])
        cmd = f"CLASSPATH={shlex.quote(remote + '/probe.apk:' + host)} app_process /system/bin " \
              f"com.lcodexs.coursealarm.HostCompatibilityProbe {shlex.quote(remote + '/contracts.json')} {shlex.quote(host)}"
        result = subprocess.run(adb + ["shell", "su", "-c", cmd], capture_output=True,
                                encoding="utf-8", errors="replace", timeout=60)
        log = args.output.parent / ("art-" + report["versionName"] + ".log")
        log.write_text(result.stdout + result.stderr, encoding="utf-8")
        marker = "COURSEALARM_COMPAT_RESULT="
        lines = [line for line in result.stdout.splitlines() if line.startswith(marker)]
        report["runtime"] = json.loads(lines[-1][len(marker):]) if lines else {
            "passed": False, "errors": ["No probe result; see " + log.name]}
        report["runtime"].update(device=args.serial, fingerprint=device, exitCode=result.returncode)
        if result.returncode != 0:
            report["runtime"]["passed"] = False
        print(report["versionName"], "ART", report["runtime"]["passed"], flush=True)
    # Exact, generated file paths only; no recursive removal of a computed directory.
    for name in ["probe.apk", "contracts.json"] + [f"host-{i}.apk" for i in range(len(reports))]:
        command(adb + ["shell", "rm", "-f", remote + "/" + name])
    command(adb + ["shell", "rmdir", remote])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, action="append", required=True)
    parser.add_argument("--contracts", type=Path, default=Path(__file__).resolve().parents[1] / "compatibility/host-contracts.json")
    parser.add_argument("--aapt2", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--probe", type=Path, help="Verification build, never installed")
    parser.add_argument("--adb")
    parser.add_argument("--serial", help="Explicit adb serial; no implicit/default target")
    args = parser.parse_args()
    if args.probe and (not args.adb or not args.serial):
        parser.error("--probe requires --adb and --serial")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    spec = json.loads(args.contracts.read_text(encoding="utf-8"))
    reports = []
    for apk in args.apk:
        report = static_check(apk, spec, args.aapt2)
        reports.append(report)
        print(report["versionName"], "DEX/resources", report["passed"], flush=True)
    output = {"scope": "Static host contracts/resources, plus isolated ART synthetic checks if requested. Not full UI or database regression.", "hosts": reports}
    if args.probe:
        try:
            runtime_check(args, reports)
        except (RuntimeError, subprocess.TimeoutExpired) as error:
            output["runtimeError"] = str(error)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if all(r["passed"] and (not args.probe or r.get("runtime", {}).get("passed")) for r in reports) else 1


if __name__ == "__main__":
    sys.exit(main())
