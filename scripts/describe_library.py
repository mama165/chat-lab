"""Read a folder of files and write what the catalog needs to know about each.

Nothing is labelled by hand. For every picture the script computes:

  - a vector of what the picture shows, with the image side of SigLIP 2
    (the text side of the same model encodes the user's description);
  - whether it was taken indoors or outdoors, with the same model;
  - the number of faces, with the YuNet detector;
  - the text visible in the picture, with RapidOCR;
  - the contents of QR codes, with OpenCV;
  - the date it was taken and its GPS position, from its EXIF data.

Documents are read page by page with pdftotext. The output is a JSON list of
entries, the format `cmd/query -library` reads.

    python scripts/describe_library.py FOLDER OUTPUT.json \\
        --siglip MODELS/siglip2 --yunet MODELS/face_detection_yunet_2023mar.onnx

Everything runs on this machine. No file leaves it, and the telemetry of the
inference runtime is switched off.
"""

import argparse
import datetime
import json
import os
import subprocess
import sys

import numpy as np
import onnxruntime as ort
from PIL import ExifTags, Image, ImageOps

ort.disable_telemetry_events()

IMAGE_EXTENSIONS = (".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".avif")
VIDEO_EXTENSIONS = (".mp4", ".mov", ".webm")
AUDIO_EXTENSIONS = (".m4a", ".mp3", ".wav", ".aiff", ".ogg")
# A face is the subject of a picture when it fills this share of it.
PORTRAIT_FACE_SHARE = 0.04
OCR_MIN_CONFIDENCE = 0.6


class ImageEncoder:
    """The image side of SigLIP 2: a picture becomes a vector."""

    def __init__(self, model_dir):
        path = os.path.join(model_dir, "onnx", "vision_model.onnx")
        self.session = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        self.input = self.session.get_inputs()[0].name
        self.outputs = [o.name for o in self.session.get_outputs()]

    def encode(self, image):
        # SigLIP resizes to a square without cropping, then maps pixels to [-1, 1].
        resized = image.convert("RGB").resize((224, 224), Image.BILINEAR)
        pixels = (np.asarray(resized, dtype=np.float32) / 255.0 - 0.5) / 0.5
        batch = pixels.transpose(2, 0, 1)[None]
        result = dict(zip(self.outputs, self.session.run(None, {self.input: batch})))
        vector = result["pooler_output"][0]
        return (vector / np.linalg.norm(vector)).astype(np.float32)


class SettingJudge:
    """Says whether a picture was taken indoors or outdoors.

    No extra model: the picture's vector is compared with the vectors of two
    sentences, with the text side of the same model. When the two are too
    close to call, nothing is said rather than a guess recorded as a fact.
    """

    INDOOR = "une photo prise à l'intérieur, dans une pièce"
    OUTDOOR = "une photo prise à l'extérieur, en plein air"
    # A poster, a screenshot or a scanned page is neither.
    NOT_A_SCENE = "une affiche, un document ou une capture d'écran avec du texte"
    MIN_GAP = 0.01

    def __init__(self, model_dir):
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        from encode_text import TextEncoder

        encoder = TextEncoder(model_dir, full=True)
        self.indoor, self.outdoor, self.flat = encoder.encode_many([self.INDOOR, self.OUTDOOR, self.NOT_A_SCENE])

    def indoor_or_none(self, vector):
        indoor, outdoor, flat = (float(vector @ v) for v in (self.indoor, self.outdoor, self.flat))
        if flat >= max(indoor, outdoor) or abs(indoor - outdoor) < self.MIN_GAP:
            return None
        return indoor > outdoor


class FaceCounter:
    def __init__(self, model_path):
        import cv2

        self.cv2 = cv2
        self.detector = cv2.FaceDetectorYN.create(model_path, "", (320, 320), 0.6, 0.3, 5000)

    def faces(self, image):
        """Return the number of faces and whether one is the subject."""
        bgr = self.cv2.cvtColor(np.asarray(image.convert("RGB")), self.cv2.COLOR_RGB2BGR)
        height, width = bgr.shape[:2]
        self.detector.setInputSize((width, height))
        _, found = self.detector.detect(bgr)
        if found is None:
            return 0, False
        largest = max(float(face[2] * face[3]) for face in found)
        return len(found), largest / float(width * height) >= PORTRAIT_FACE_SHARE


def flatten(image):
    """Return the picture on a white background: transparency reads as black otherwise."""
    if image.mode in ("RGBA", "LA", "P"):
        image = image.convert("RGBA")
        image = Image.alpha_composite(Image.new("RGBA", image.size, (255, 255, 255, 255)), image)
    return image.convert("RGB")


class TextReader:
    """Reads the text visible in a picture.

    The picture is read three ways (as is with a margin, in contrasted grey,
    and inverted) and the words of the three readings are gathered: light text
    on a coloured sign is missed by one reading and found by another. The
    result feeds a search index, where a word read twice costs nothing and a
    word missed cannot be found.
    """

    def __init__(self):
        from rapidocr_onnxruntime import RapidOCR

        self.ocr = RapidOCR()

    def read(self, image):
        image = flatten(image)
        grey = ImageOps.autocontrast(image.convert("L")).convert("RGB")
        variants = [
            ImageOps.expand(image, border=60, fill=image.getpixel((2, 2))),
            grey,
            ImageOps.invert(grey),
        ]
        words, seen = [], set()
        for variant in variants:
            result, _ = self.ocr(np.asarray(variant)[:, :, ::-1])
            for line in result or []:
                if float(line[2]) < OCR_MIN_CONFIDENCE:
                    continue
                # A capital I before an apostrophe is a misread l: "I'herbe".
                text = line[1].replace("I'", "l'").replace("I’", "l’")
                for word in text.split():
                    key = word.lower()
                    if key not in seen:
                        seen.add(key)
                        words.append(word)
        return " ".join(words)


def read_codes(image):
    import cv2

    bgr = cv2.cvtColor(np.asarray(image.convert("RGB")), cv2.COLOR_RGB2BGR)
    try:
        found, texts, _, _ = cv2.QRCodeDetector().detectAndDecodeMulti(bgr)
    except cv2.error:
        return []
    return [text for text in (texts if found else []) if text]


def exif_facts(image):
    """Return the date a picture was taken and its GPS position, when it says so."""
    facts = {}
    try:
        exif = image.getexif()
    except Exception:  # a damaged EXIF block is not a reason to drop the picture
        return facts
    details = exif.get_ifd(ExifTags.IFD.Exif)
    taken = details.get(ExifTags.Base.DateTimeOriginal) or exif.get(ExifTags.Base.DateTime)
    if taken:
        try:
            when = datetime.datetime.strptime(str(taken), "%Y:%m:%d %H:%M:%S")
            facts["taken_at"] = when.replace(tzinfo=datetime.timezone.utc).isoformat().replace("+00:00", "Z")
        except ValueError:
            pass
    gps = exif.get_ifd(ExifTags.IFD.GPSInfo)

    def degrees(value, reference):
        d, m, s = (float(x) for x in value)
        sign = -1 if reference in ("S", "W") else 1
        return sign * (d + m / 60 + s / 3600)

    try:
        if ExifTags.GPS.GPSLatitude in gps and ExifTags.GPS.GPSLongitude in gps:
            facts["latitude"] = round(degrees(gps[ExifTags.GPS.GPSLatitude], gps.get(ExifTags.GPS.GPSLatitudeRef, "N")), 6)
            facts["longitude"] = round(degrees(gps[ExifTags.GPS.GPSLongitude], gps.get(ExifTags.GPS.GPSLongitudeRef, "E")), 6)
    except (TypeError, ValueError, ZeroDivisionError):
        pass
    return facts


def pdf_pages(path):
    info = subprocess.run(["pdfinfo", path], capture_output=True, text=True, check=True).stdout
    count = next((int(line.split(":")[1]) for line in info.splitlines() if line.startswith("Pages:")), 0)
    pages = []
    for page in range(1, count + 1):
        text = subprocess.run(
            ["pdftotext", "-layout", "-f", str(page), "-l", str(page), path, "-"],
            capture_output=True, text=True, check=True,
        ).stdout
        pages.append(text.strip())
    return pages


def kind_of(name):
    lower = name.lower()
    if lower.endswith(".pdf"):
        return "pdf"
    if lower.endswith(IMAGE_EXTENSIONS):
        return "image"
    if lower.endswith(VIDEO_EXTENSIONS):
        return "video"
    if lower.endswith(AUDIO_EXTENSIONS):
        return "audio"
    return None


def describe(root, encoder, faces, reader, setting=None):
    entries, skipped = [], []
    for directory, _, names in sorted(os.walk(root)):
        for name in sorted(names):
            kind = kind_of(name)
            if kind is None:
                continue
            path = os.path.join(directory, name)
            relative = os.path.relpath(path, root).replace(os.sep, "/")
            stat = os.stat(path)
            added = datetime.datetime.fromtimestamp(stat.st_mtime, datetime.timezone.utc)
            entry = {
                "id": relative, "path": path, "name": name, "kind": kind, "size": stat.st_size,
                "added_at": added.isoformat().replace("+00:00", "Z"),
            }
            try:
                if kind == "image":
                    with Image.open(path) as opened:
                        entry.update(exif_facts(opened))
                        image = flatten(ImageOps.exif_transpose(opened))
                        vector = encoder.encode(image)
                        entry["vectors"] = [[round(float(x), 6) for x in vector]]
                        if setting is not None:
                            indoor = setting.indoor_or_none(vector)
                            if indoor is not None:
                                entry["indoor"] = indoor
                        if faces is not None:
                            entry["faces"], entry["portrait"] = faces.faces(image)
                        if reader is not None:
                            text = reader.read(image)
                            if text:
                                entry["text"] = text
                        codes = read_codes(image)
                        if codes:
                            entry["codes"] = codes
                elif kind == "pdf":
                    entry["pages"] = pdf_pages(path)
            except Exception as error:  # one unreadable file must not stop the pass
                skipped.append(f"{relative}: {type(error).__name__}: {error}")
                continue
            entries.append(entry)
    return entries, skipped


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder")
    parser.add_argument("output")
    parser.add_argument("--siglip", required=True, help="folder of the SigLIP 2 ONNX model")
    parser.add_argument("--yunet", help="YuNet face detector; without it faces are not counted")
    parser.add_argument("--no-text", action="store_true", help="do not read the text visible in pictures")
    args = parser.parse_args(argv)

    encoder = ImageEncoder(args.siglip)
    faces = FaceCounter(args.yunet) if args.yunet else None
    reader = None if args.no_text else TextReader()
    entries, skipped = describe(args.folder, encoder, faces, reader, SettingJudge(args.siglip))
    with open(args.output, "w", encoding="utf-8") as out:
        json.dump(entries, out, ensure_ascii=False)
    print(f"{len(entries)} fichiers décrits, {len(skipped)} ignorés", file=sys.stderr)
    for line in skipped:
        print("  ignoré —", line, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
