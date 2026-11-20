# Course activity checks

Run the pure occurrence timeline checks without Android or Gradle:

```powershell
python tools/run_live_checks.py --jdk ../toolchain/jdk21
```

They cover 45, 90 and 100 minute classes, continuous progress through breaks,
cross-midnight and daylight-saving timestamps, start/end/retention boundaries,
manual dismissal precedence, independent instance identities and invalid inputs.

Run DEX and signed template checks against the three locally supplied calendar APKs:

```powershell
python tools/verify_live_apks.py --apk "<17.3.70.apk>" --apk "<17.6.20.apk>" --apk "<17.8.0.apk>" --aapt2 "<aapt2.exe>" --output "<report.json>"
```

The script checks host interfaces including restored Seedling identities, resolves the notification factory by its
full signature, and records whether the unmodified signed Seedling templates
contain progress nodes or a do-not-disturb binding. Provider `call` is inherited
from Android and is explicitly reported as an external framework dependency.

These checks do not install packages, query real calendar data, or establish
successful notification delivery, system-card rendering, DND behavior or UI
compatibility. Those require the separate device checks.
