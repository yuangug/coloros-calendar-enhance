"""Compile and run pure course occurrence tests in a temporary output directory."""
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
    javac, java = (args.jdk / "bin" / (name + suffix) for name in ("javac", "java"))
    production = root / "app/src/main/java/com/lcodexs/coursealarm"
    test = root / "tools/livecheck/CourseOccurrenceCheck.java"
    with tempfile.TemporaryDirectory(prefix="coursealarm-live-check-") as output:
        subprocess.run([str(javac), "-encoding", "UTF-8", "-d", output,
                        str(production / "CourseOccurrence.java"), str(production / "CourseAstraStyle.java"),
                        str(test)], check=True)
        subprocess.run([str(java), "-cp", output, "com.lcodexs.coursealarm.CourseOccurrenceCheck"], check=True)


if __name__ == "__main__":
    main()
