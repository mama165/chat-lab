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
LAUNCHER = "com.google.android.apps.nexuslauncher"
HERE = os.path.dirname(os.path.abspath(__file__))
APK = os.environ.get("APK", os.path.join(HERE, "..", "..", "ci-apk", "app-debug.apk"))
FIXTURE = os.path.join(HERE, "fixtures", "library.json")
TEST_IMAGES = os.path.join(HERE, "fixtures", "test")
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
        label = f"{node.get('text') or ''} {node.get('content-desc') or ''}"
        same_text = text is None or text in label
        same_class = cls is None or node.get("class") == cls
        if same_text and same_class:
            return node
    return None


def tap(text=None, cls=None):
    node = None
    tree = None
    for _ in range(15):
        tree = screen_tree()
        node = find(tree, text, cls)
        if node is not None:
            break
        time.sleep(1)
    if node is None:
        # Keep what was on screen, to see why the element was not there.
        os.makedirs(OUT, exist_ok=True)
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=open(os.path.join(OUT, "erreur.png"), "wb"))
        ET.ElementTree(tree).write(os.path.join(OUT, "erreur-ecran.xml"))
        # Print what is on screen, so the log shows why the element was not found.
        print("écran au moment du blocage :")
        for node in tree.iter("node"):
            label = f"{node.get('text') or ''} | {node.get('content-desc') or ''}"
            if label.strip(" |"):
                print(f"  {node.get('class')} : {label}")
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


def dismiss_system_dialogs():
    # On a first boot, the launcher may report that it is not responding and cover the app.
    # "Wait" lets it finish starting.
    for _ in range(3):
        if find(screen_tree(), "isn't responding") is None:
            return
        print("alerte système : on attend")
        tap(text="Wait")
        time.sleep(3)
    if find(screen_tree(), "isn't responding") is not None:
        # The alert keeps coming back: restart the launcher itself, then go to the home screen.
        print("alerte système persistante : redémarrage du lanceur")
        adb("shell", "am", "force-stop", LAUNCHER)
        time.sleep(8)
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(5)


def relaunch():
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
    time.sleep(4)
    dismiss_system_dialogs()


def clear_text():
    tap(cls="android.widget.EditText")
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(40):
        adb("shell", "input", "keyevent", "KEYCODE_DEL")
    time.sleep(1)


def main():
    wait_for_boot()
    time.sleep(20)  # let the launcher finish its first start
    adb("install", "-r", "-g", APK)
    # First launch creates the app's private storage; then the test data goes in.
    relaunch()
    adb("push", FIXTURE, "/data/local/tmp/library.json")
    adb("shell", "run-as", PACKAGE, "cp", "/data/local/tmp/library.json", "files/library.json")
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files/test")
    for name in sorted(os.listdir(TEST_IMAGES)):
        adb("push", os.path.join(TEST_IMAGES, name), f"/data/local/tmp/{name}")
        adb("shell", "run-as", PACKAGE, "cp", f"/data/local/tmp/{name}", "files/test/")
    adb("push", os.path.join(HERE, "fixtures", "query_vectors.json"), "/data/local/tmp/query_vectors.json")
    adb("shell", "run-as", PACKAGE, "cp", "/data/local/tmp/query_vectors.json", "files/query_vectors.json")
    relaunch()

    shot("01-rechercher-vide.png")
    # Ten searches, each with its own screenshot: words first, then descriptions.
    searches = [
        ("Mots", "facture"),
        ("Mots", "wifi"),
        ("Mots", "sortie"),
        ("Mots", "herbe"),
        ("Mots", "soldes"),
        ("Description", "une facture"),
        ("Description", "une sortie de secours"),
        ("Description", "un ticket wifi"),
        ("Description", "deux personnes"),
        ("Description", "un paysage avec des nuages"),
    ]
    for index, (mode, query) in enumerate(searches, start=2):
        tap(text=mode)
        clear_text()
        type_text(query)
        shot(f"{index:02d}-{mode.lower()}-{query.replace(' ', '-')}.png")

    # Détail : on ouvre la photo « facture_fictive » depuis les résultats, puis on revient.
    tap(text="Description")
    clear_text()
    type_text("une facture")
    tap(text="facture_fictive")
    time.sleep(2)
    shot("13-detail.png")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(2)

    tap(text="Analyser")
    shot("12-analyser.png")
    return 0


if __name__ == "__main__":
    sys.exit(main())
