"""Measure face counting on WIDER FACE, a public benchmark of 3 226 photographs
whose faces were all marked by hand (validation part, 39 708 faces).

Nobody here labelled anything. For each photograph, the faces found by the
detector are compared with the ones marked by people.

    python scripts/measure_faces.py MODEL.onnx WIDER_FACE_DIR [--limit N]

WIDER_FACE_DIR holds WIDER_val.zip and wider_face_split/, from the Hugging Face
dataset CUHK-CSE/wider_face (licence CC BY-NC-ND 4.0: for measuring only, the
pictures are read from the archive and not copied anywhere).

Two things are reported.

  Detection: of the faces marked by people, how many are found (recall), and
  of the faces found, how many are real (precision), by face size. A face is
  found when the detector's box covers at least half of the marked one.

  Counting, which is what a search uses ("deux personnes"): on the
  photographs whose faces are all at least 40 pixels high, how often the number
  of faces found is exactly right, or off by one.
"""

import io
import json
import os
import sys
import zipfile

import cv2
import numpy as np

SIZES = [("20-40 px", 20, 40), ("40-80 px", 40, 80), ("80 px et plus", 80, 10_000)]
COUNT_MIN_HEIGHT = 40
MIN_SCORE = 0.6


def read_annotations(path):
    """Yield (relative path, [(x, y, w, h), ...]) for the valid faces of each photograph."""
    with open(path, encoding="utf-8") as lines:
        rows = [line.strip() for line in lines]
    index = 0
    while index < len(rows):
        name, count = rows[index], int(rows[index + 1])
        index += 2
        faces = []
        for row in rows[index:index + max(count, 1)]:
            values = [int(v) for v in row.split()]
            x, y, w, h, invalid = values[0], values[1], values[2], values[3], values[7]
            if count > 0 and w > 0 and h > 0 and not invalid:
                faces.append((x, y, w, h))
        index += max(count, 1)
        yield name, faces


def overlap(face, box):
    """Share of the marked face covered by the detected box."""
    x1, y1 = max(face[0], box[0]), max(face[1], box[1])
    x2, y2 = min(face[0] + face[2], box[0] + box[2]), min(face[1] + face[3], box[1] + box[3])
    inside = max(0, x2 - x1) * max(0, y2 - y1)
    return inside / float(face[2] * face[3])


def iou(face, box):
    x1, y1 = max(face[0], box[0]), max(face[1], box[1])
    x2, y2 = min(face[0] + face[2], box[0] + box[2]), min(face[1] + face[3], box[1] + box[3])
    inside = max(0, x2 - x1) * max(0, y2 - y1)
    union = face[2] * face[3] + box[2] * box[3] - inside
    return inside / float(union) if union else 0.0


def main(argv):
    model, directory = argv[0], argv[1]
    limit = int(argv[argv.index("--limit") + 1]) if "--limit" in argv else None
    detector = cv2.FaceDetectorYN.create(model, "", (320, 320), MIN_SCORE, 0.3, 5000)
    archive = zipfile.ZipFile(os.path.join(directory, "WIDER_val.zip"))
    names = {os.path.basename(n): n for n in archive.namelist() if n.endswith(".jpg")}
    annotations = list(read_annotations(os.path.join(directory, "wider_face_split", "wider_face_val_bbx_gt.txt")))
    if limit:
        annotations = annotations[:limit]

    marked = {label: 0 for label, _, _ in SIZES}
    found = {label: 0 for label, _, _ in SIZES}
    detected, real = 0, 0
    counted = exact = within_one = 0
    for position, (name, faces) in enumerate(annotations):
        data = archive.read(names[os.path.basename(name)])
        image = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
        detector.setInputSize((image.shape[1], image.shape[0]))
        _, result = detector.detect(image)
        boxes = [] if result is None else [tuple(int(v) for v in row[:4]) for row in result]

        for face in faces:
            for label, low, high in SIZES:
                if low <= face[3] < high:
                    marked[label] += 1
                    if any(overlap(face, box) >= 0.5 for box in boxes):
                        found[label] += 1
        # A detection is real when it lands on a marked face; faces too small
        # to be marked reliably (under 20 px) are not held against the detector.
        for box in boxes:
            if box[3] < 20:
                continue
            detected += 1
            if any(iou(face, box) >= 0.4 or overlap(face, box) >= 0.6 for face in faces):
                real += 1

        if faces and min(face[3] for face in faces) >= COUNT_MIN_HEIGHT:
            counted += 1
            seen = sum(1 for box in boxes if box[3] >= 20)
            exact += seen == len(faces)
            within_one += abs(seen - len(faces)) <= 1
        if (position + 1) % 500 == 0:
            print(f"  {position + 1} photographies", file=sys.stderr, flush=True)

    report = {
        "photographs": len(annotations),
        "recall_by_face_height": {label: round(found[label] / marked[label], 3) for label, _, _ in SIZES if marked[label]},
        "faces_marked_by_height": marked,
        "precision": round(real / detected, 3) if detected else None,
        "counting_on_photographs_with_all_faces_at_least_40_px": {
            "photographs": counted,
            "exact": round(exact / counted, 3) if counted else None,
            "within_one": round(within_one / counted, 3) if counted else None,
        },
    }
    print(json.dumps(report, indent=1, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
