# Course activity checks

Run the pure occurrence timeline checks without Android or Gradle:

```powershell
python tools/run_live_checks.py --jdk ../toolchain/jdk21
```

They cover 45, 90 and 100 minute classes, continuous progress through breaks,
cross-midnight and daylight-saving timestamps, start/end/retention boundaries,
manual dismissal precedence, independent instance identities and invalid inputs.
The opt-in reminder window is also checked at the configured lead time, after
ten minutes of class, across breaks and adjacent lessons, and at dismissal and
class end. Its dispatch gate covers a native reminder racing a scheduled retry.

The stage tests cover unequal periods, real versus zero-length breaks, lookup against
the actual event clock, and the SDK's 16-label limit. The standalone Android probe
in `android/AstraStageProbe.java` also builds a real staged and ordinary Astra SDK card
without posting a notification.

Run DEX and signed template checks against the three locally supplied calendar APKs:

```powershell
python tools/verify_live_apks.py --apk "<17.3.70.apk>" --apk "<17.6.20.apk>" --apk "<17.8.0.apk>" --aapt2 "<aapt2.exe>" --output "<report.json>"
```

The script checks host interfaces including the course sender, model conversion and restored Seedling identities, resolves the notification factory by its
full signature, and records whether the unmodified signed Seedling templates
contain progress nodes or a do-not-disturb binding. Provider `call` is inherited
from Android and is explicitly reported as an external framework dependency.

These checks do not install packages, query real calendar data, or establish
successful notification delivery, system-card rendering, DND behavior or UI
compatibility. Those require the separate device checks.

The isolated ART host probe also constructs a normal course reminder with each
Calendar APK's own class loader and checks the occurrence ID, reminder/start/end
times, time zone and course account. This does not publish a card on the phone.
