"""Run table loading, week completion and page selection checks without changing persistent data."""
import argparse
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    suffix = ".exe" if (args.jdk / "bin/javac.exe").exists() else ""
    main_src = root / "app/src/main/java/com/lcodexs/coursealarm"
    checks = ["InFlightReadsCheck", "WeekFinishCheck"]
    sources = [main_src / f"{name}.java" for name in ("InFlightReads", "WeekFinishSchedule", "WeekPageSelection")]
    sources += [root / "tools/tableloadcheck" / f"{name}.java" for name in checks]
    with tempfile.TemporaryDirectory(prefix="coursealarm-table-read-") as output:
        subprocess.run([str(args.jdk / "bin" / ("javac" + suffix)), "-encoding", "UTF-8", "-d", output,
                        *map(str, sources)], check=True)
        for name in checks:
            subprocess.run([str(args.jdk / "bin" / ("java" + suffix)), "-cp", output,
                            f"com.lcodexs.coursealarm.{name}"], check=True, timeout=30)


if __name__ == "__main__":
    main()
