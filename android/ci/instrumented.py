#!/usr/bin/env python3
"""Lance les tests d'interface Compose (androidTest) sur l'émulateur déjà démarré.

L'APK de l'application est installée par capture.py ; ce script installe l'APK de test,
lance les tests et échoue si l'un d'eux échoue. Le compte rendu est gardé avec les captures.
"""
import glob
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "screens")
RUNNER = "com.example.chatlab.test/androidx.test.runner.AndroidJUnitRunner"


def main():
    apks = glob.glob(os.path.join(os.getcwd(), "ci-test-apk", "*.apk"))
    if not apks:
        raise SystemExit("APK de test introuvable dans ci-test-apk")
    subprocess.run(["adb", "install", "-r", "-g", apks[0]], check=True)
    result = subprocess.run(["adb", "shell", "am", "instrument", "-w", "-r", RUNNER], capture_output=True, text=True)
    output = result.stdout + result.stderr
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "tests-emulateur.txt"), "w") as file:
        file.write(output)
    # Résumé lisible : le nom de chaque test et son résultat.
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: test=") or "INSTRUMENTATION_STATUS: stack=" in line:
            print(line)
        if line.startswith("INSTRUMENTATION_CODE") or line.startswith("OK (") or line.startswith("FAILURES") or line.startswith("Tests run"):
            print(line)
    if "FAILURES!!!" in output or "Process crashed" in output or "OK (" not in output:
        print(output[-6000:])
        raise SystemExit("des tests d'interface ont échoué")
    print("tests d'interface : tous passés")


if __name__ == "__main__":
    sys.exit(main())
