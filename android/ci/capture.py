"""Drive ChatLab on an Android emulator and save a screenshot of each step.

Run by the Android workflow, after the debug APK has been built. It installs the
app, puts a small example library in its private storage, then opens each tab and
types a few searches, saving one PNG per step into android/ci/screens.

It finds buttons by their text (uiautomator), so it keeps working when the layout moves.
"""

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.example.chatlab"
HERE = os.path.dirname(os.path.abspath(__file__))
APK = os.environ.get("APK", os.path.join(HERE, "..", "..", "ci-apk", "app-debug.apk"))
FIXTURE = os.path.join(HERE, "fixtures", "library.json")
OUT = os.path.join(HERE, "screens")


def adb(*args, check=True):
    result = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} a échoué : {result.stderr.strip()}")
    return result.stdout


def wait_for_boot():
    adb("wait-for-device")
    while adb("shell", "getprop", "sys.boot_completed", check=False).strip() != "1":
        time.sleep(2)
    time.sleep(3)


def screen_tree():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        xml = adb("shell", "cat", "/sdcard/ui.xml", check=False)
        if xml.strip().startswith("<?xml"):
            return ET.fromstring(xml)
        time.sleep(1)
    raise SystemExit("impossible de lire l'écran de l'émulateur")


def find(root, text=None, cls=None):
    for node in root.iter("node"):
        same_text = text is None or text in (node.get("text"), node.get("content-desc"))
        same_class = cls is None or node.get("class") == cls
        if same_text and same_class:
            return node
    return None


def tap(text=None, cls=None):
    node = None
    for _ in range(5):
        node = find(screen_tree(), text, cls)
        if node is not None:
            break
        time.sleep(1)
    if node is None:
        raise SystemExit(f"élément introuvable : {text or cls}")
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(1.5)


def type_text(value):
    tap(cls="android.widget.EditText")
    adb("shell", "input", "text", value.replace(" ", "%s"))
    time.sleep(2)


def shot(name):
    os.makedirs(OUT, exist_ok=True)
    data = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
    with open(os.path.join(OUT, name), "wb") as file:
        file.write(data)
    print("capture :", name)


def relaunch():
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(4)


def main():
    wait_for_boot()
    adb("install", "-r", "-g", APK)
    # First launch creates the app's private storage, then the example library goes in.
    relaunch()
    adb("push", FIXTURE, "/data/local/tmp/library.json")
    adb("shell", "run-as", PACKAGE, "cp", "/data/local/tmp/library.json", "files/library.json")
    relaunch()

    shot("01-rechercher-vide.png")
    tap(text="Mots")
    type_text("facture")
    shot("02-mots-facture.png")

    tap(text="Analyser")
    shot("03-analyser.png")

    tap(text="Rechercher")
    tap(text="Description")
    type_text("un paysage avec des nuages")
    shot("04-description.png")
    return 0


if __name__ == "__main__":
    sys.exit(main())
