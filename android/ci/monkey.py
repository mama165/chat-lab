#!/usr/bin/env python3
"""Test de robustesse : le « monkey » d'Android envoie des milliers d'actions au hasard.

Il reste dans l'application (-p) et ne vérifie pas les résultats, seulement qu'elle ne plante
pas et ne se fige pas. La graine fixe (-s) permet de rejouer exactement la même suite.
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "screens")
PACKAGE = "com.example.chatlab"
SEEDS = ["42", "7", "2026"]
EVENTS = "2500"


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True)


def run(seed):
    adb("logcat", "-c")
    command = [
        "shell", "monkey", "-p", PACKAGE, "-s", seed,
        "--throttle", "60",
        "--pct-syskeys", "2",
        "--pct-appswitch", "3",
        "-v", "-v", EVENTS,
    ]
    result = adb(*command)
    output = result.stdout + result.stderr
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, f"monkey-{seed}.txt"), "w") as file:
        file.write(output)
    failed = any(mark in output for mark in ("// CRASH", "// NOT RESPONDING", "** Monkey aborted"))
    if failed or "Monkey finished" not in output:
        # La trace du plantage, côté appli, pour savoir quelle ligne est en cause.
        log = adb("logcat", "-d", "-v", "brief", "AndroidRuntime:E", "*:S").stdout
        with open(os.path.join(OUT, f"monkey-plantage-{seed}.txt"), "w") as file:
            file.write(log)
        print(output[-4000:])
        print(log[-4000:])
        raise SystemExit(f"le monkey a fait planter l'application (graine {seed})")
    print(f"monkey : {EVENTS} actions au hasard (graine {seed}), aucun plantage")


def main():
    for seed in SEEDS:
        run(seed)


if __name__ == "__main__":
    sys.exit(main())
