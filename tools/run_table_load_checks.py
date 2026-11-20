"""Run pending-read concurrency checks without Android, Gradle, or persistent data."""
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
    sources = [root / "app/src/main/java/com/lcodexs/coursealarm/InFlightReads.java",
               root / "tools/tableloadcheck/InFlightReadsCheck.java"]
    with tempfile.TemporaryDirectory(prefix="coursealarm-table-read-") as output:
        subprocess.run([str(args.jdk / "bin" / ("javac" + suffix)), "-encoding", "UTF-8", "-d", output,
                        *map(str, sources)], check=True)
        subprocess.run([str(args.jdk / "bin" / ("java" + suffix)), "-cp", output,
                        "com.lcodexs.coursealarm.InFlightReadsCheck"], check=True, timeout=30)


if __name__ == "__main__":
    main()
