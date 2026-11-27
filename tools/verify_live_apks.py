"""Verify the read-only host contracts used by course live activities.

This reads DEX metadata and signed UPK template contents from local APKs. It never
executes a host, edits an APK, installs a package, or claims UI/device validation.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

from dex_contracts import read_apk, verify_contract


ROOT = "com.android.calendar.seedling.notificationfluidcard."
INFO = "Lcom/android/calendar/alerts/INotificationInfo;"
MODEL = "Lcom/android/calendar/seedling/notificationfluidcard/entity/FluidSeedingModel;"
CARD = "Lcom/oplus/pantanal/seedling/bean/SeedlingCard;"
ICARD = "Lcom/android/calendar/seedling/notificationfluidcard/card/IFluidSeedingCard;"
FACTORY_DESCRIPTOR = "(Landroid/content/Context;" + INFO + "IZLjava/lang/String;Z)Landroid/app/Notification;"
FACTORY_CANDIDATES = ("com.android.calendar.alerts.l", "com.android.calendar.alerts.o")


def method(owner, name, descriptor, static=False):
    return dict(kind="method", owner=owner, name=name, descriptor=descriptor, static=static)


def field(owner, name, descriptor, static=False):
    return dict(kind="field", owner=owner, name=name, descriptor=descriptor, static=static)


def contracts():
    manager = ROOT + "FluidSeedingManager"
    abstract_card = ROOT + "card.AbsFluidSeedingCard"
    proxy = ROOT + "card.FluidSeedingCardProxy"
    model = ROOT + "entity.FluidSeedingModel"
    notification = "com.android.calendar.alerts.EventNotificationInfo"
    event = "com.coloros.calendar.foundation.databasedaolib.entities.orm.EventOrm"
    provider = "com.android.calendar.card.CourseContentProvider"
    seedling = "com.oplus.pantanal.seedling.bean.SeedlingCard"
    result = [
        method("com.android.calendar.oppo.alerts.OppoAlertActivity", "onCreate", "(Landroid/os/Bundle;)V"),
        method("com.android.calendar.alerts.DeleteAlertReceiver", "onReceive", "(Landroid/content/Context;Landroid/content/Intent;)V"),
        field(manager, "INSTANCE", "L" + manager.replace(".", "/") + ";", True),
        method(manager, "getFluidSeedingCards", "()Ljava/util/concurrent/ConcurrentHashMap;"),
        method(manager, "getFluidSeedingCards", "(Ljava/lang/String;)" + ICARD),
        method(manager, "getFluidSeedingModel", "(Ljava/lang/String;)" + MODEL),
        method(manager, "closeFluidSeedingCard", "(J)V"),
        method(manager, "closeFluidSeedingCard", "(Ljava/lang/String;)V"),
        method(manager, "updateFluidSeedingCards", "()V"),
        method(manager, "handleMethodCalls", "(Ljava/lang/String;Landroid/os/Bundle;)V"),
        method(manager, "onUnSubscribed", "(Landroid/content/Context;" + CARD + ")V"),
        method(manager, "onSubscribed", "(Landroid/content/Context;" + CARD + ")V"),
        method(manager, "sendFluidSeedingCard", "(" + INFO + ")V"),
        method(abstract_card, "getNowCard", "()" + CARD),
        method(abstract_card, "isClosedReminder", "()Z"),
        method(abstract_card, "closeFluidCard", "()V"),
        method(abstract_card, "updateFluidCard", "(Ljava/lang/Boolean;)V"),
        method(abstract_card, "checkAllUnSubscribed", "()Z"),
        field(proxy, "$$delegate_0", ICARD),
        method(proxy, "updateFluidCard", "(Ljava/lang/Boolean;)V"),
        method(proxy, "closeFluidCard", "()V"),
        method(proxy, "checkAllUnSubscribed", "()Z"),
        method(ROOT + "NotificationFluidSeedingHelper", "buildCardJSON",
               "(Ljava/lang/String;Landroid/net/Uri;" + MODEL + ")Lkotlin/Pair;", True),
        method(ROOT + "NotificationFluidSeedingHelper", "closeFluidCard", "(Ljava/lang/String;" + MODEL + ")V"),
        method(ROOT + "NotificationFluidSeedingHelper", "updateFluidCard", "(" + CARD + MODEL + "Ljava/lang/Boolean;)V", True),
        field(ROOT + "NotificationFluidSeedingHelper", "INSTANCE", "Lcom/android/calendar/seedling/notificationfluidcard/NotificationFluidSeedingHelper;", True),
        method(seedling, "build", "(Ljava/lang/String;)" + CARD, True),
        field("com.oplus.pantanal.seedling.bean.SeedlingHostEnum", "AOD", "Lcom/oplus/pantanal/seedling/bean/SeedlingHostEnum;", True),
        method("com.oplus.pantanal.seedling.update.SeedlingCardOptions", "getShowHostMap", "()Ljava/util/Map;"),
        method("com.oplus.pantanal.seedling.update.SeedlingCardOptions", "setShowHostMap", "(Ljava/util/Map;)V"),
        method(seedling, "getWidgetCode", "()Ljava/lang/String;"),
        method(seedling, "getServiceInstanceId", "()Ljava/lang/String;"),
        method(seedling, "setServiceInstanceId", "(Ljava/lang/String;)V"),
        method("kotlin.Pair", "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V"),
        method("kotlin.Pair", "getFirst", "()Ljava/lang/Object;"),
        method("kotlin.Pair", "getSecond", "()Ljava/lang/Object;"),
        method(provider, "courseBaseInfo", "([Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;"),
        method(provider, "courseDetailsWeek", "([Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;"),
        method(notification, "isCourse", "()Z"),
        method(notification, "setAccountType", "(Ljava/lang/String;)V"),
        method(model, "toNotificationInfo", "()" + INFO),
        method(ROOT + "NotificationFluidSeedingHelper", "isSupportFluidCloud", "(Z)Z", True),
        method("com.oplus.pantanal.seedling.util.BaseTool", "genServiceInstanceId", "()Ljava/lang/String;"),
        method(notification, "getAlertFeatures", "()I"),
        method("com.android.calendar.alerts.INotificationInfo", "isForceAlert", "()Z"),
        method("com.coloros.calendar.widget.bottomsheetdialog.CourseListInfoFragment", "initView", "(Landroid/view/View;)V"),
        method("com.coloros.calendar.widget.bottomsheetdialog.CourseListInfoFragment", "onStart", "()V"),
    ]
    result += [method(model, name, "()J") for name in ("getEventId", "getBeginTime", "getEndTime", "getAlarmTime")]
    result += [method(model, "getTimingDesc", "()Lkotlin/Pair;")]
    result += [method(model, "getExtendedProperties", "()I")]
    for subtype in (ROOT + "entity.NormalReminderModel", ROOT + "entity.ForceReminderModel"):
        result += [method(subtype, "<init>", "(JLjava/lang/String;ZJJJJI" + "Ljava/lang/String;" * 6 + ")V"),
                   method(subtype, "isForceAlert", "()Z")]
    result += [method(notification, name, "()J") for name in ("getTargetId", "getBeginTime", "getEndTime", "getAlarmTime")]
    result += [method(event, "getId", "()J")]
    result += [method(event, name, "()Ljava/lang/Long;") for name in ("getDtstart", "getDtend")]
    result += [method(event, name, "()Ljava/lang/String;") for name in ("getDuration", "getExdate", "getRdate", "getExrule", "getEventTimezone")]
    return result


def template_report(apk):
    reports = []
    with zipfile.ZipFile(apk) as package:
        for filename in ("NotificationFluidSeedlingCard.upk", "AlarmFluidSeedlingCard.upk"):
            payload = package.read("assets/upk/" + filename)
            with zipfile.ZipFile(io.BytesIO(payload)) as template:
                page = json.loads(template.read("pages/index.json"))
                config = json.loads(template.read("config.json"))
                entries = sorted({entry for device in config["runtime"]["support-hardware"]["device"]
                                  if device["type"] == "PHONE" for entry in device["entry"]})
                types = set()

                def visit(value):
                    if isinstance(value, dict):
                        if isinstance(value.get("type"), str):
                            types.add(value["type"])
                        for nested in value.values():
                            visit(nested)
                    elif isinstance(value, list):
                        for nested in value:
                            visit(nested)

                visit(page)
                reports.append({
                    "asset": filename,
                    "sha256": hashlib.sha256(payload).hexdigest(),
                    "signed": any(name.startswith("META-INF/") and name.endswith(".RSA") for name in template.namelist()),
                    "nodeTypes": sorted(types), "phoneEntries": entries, "declaresAod": "aod" in entries,
                    "progressNode": any("progress" in name.lower() for name in types),
                    "dndBinding": bool(re.search(r"\bdnd\b|doNotDisturb|免打扰", json.dumps(page, ensure_ascii=False), re.I)),
                    "scope": "Template structure only; arbitrary publisher metadata cannot establish rendered widget support.",
                })
    return reports


def check(apk, source, aapt2=None):
    index = read_apk(apk)
    errors = []
    checks = contracts()
    for contract in checks:
        reason = verify_contract(index, contract)
        if reason:
            errors.append({"contract": contract, "reason": reason})
    factories = [(owner, name) for owner in FACTORY_CANDIDATES
                 for name, descriptor, flags in index.get(owner, {}).get("methods", [])
                 if descriptor == FACTORY_DESCRIPTOR and flags & 8]
    if len(factories) != 1:
        errors.append({"selector": "notificationFactory", "reason": f"Expected exactly one signature match: {factories}"})
    elif factories[0][0] not in source:
        errors.append({"selector": "notificationFactory", "reason": f"Implementation does not include verified owner {factories[0][0]}"})
    provider_super = index.get("com.android.calendar.card.CourseContentProvider", {}).get("super")
    if provider_super != "android.content.ContentProvider":
        errors.append({"selector": "providerCall", "reason": "Unexpected base class " + str(provider_super)})
    version = None
    if aapt2:
        badging = subprocess.check_output([str(aapt2), "dump", "badging", str(apk)], text=True, encoding="utf-8")
        found = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
        if not found or found[1] != "com.coloros.calendar":
            errors.append({"package": "Expected com.coloros.calendar"})
        else:
            version = {"package": found[1], "versionCode": int(found[2]), "versionName": found[3]}
    return {"apk": str(apk.resolve()), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "version": version, "contractCount": len(checks), "classes": len(index),
            "notificationFactory": [owner + "#" + name + FACTORY_DESCRIPTOR for owner, name in factories],
            "frameworkInherited": ["CourseContentProvider -> android.content.ContentProvider.call(String,String,Bundle)"],
            "templates": template_report(apk), "passed": not errors, "errors": errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--aapt2", type=Path)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1] /
                        "app/src/main/java/com/lcodexs/coursealarm/CourseLiveHooks.java")
    args = parser.parse_args()
    source = args.source.read_text(encoding="utf-8")
    reports = [check(apk, source, args.aapt2) for apk in args.apk]
    output = {"scope": "Static DEX contracts and signed template structure only. Not runtime, UI, notification delivery, or database validation.",
              "hosts": reports}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for report in reports:
        print(report["version"] or Path(report["apk"]).name, "PASS" if report["passed"] else "FAIL",
              f"({report['contractCount']} contracts)", flush=True)
        for error in report["errors"]:
            print(json.dumps(error, ensure_ascii=False), flush=True)
    return 0 if all(report["passed"] for report in reports) else 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
