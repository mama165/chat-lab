"""Measure how well words are read, on TextOCR, a public benchmark.

TextOCR_OCR (Hugging Face dataset MiXaiLL76/TextOCR_OCR, licence MIT) holds
14 669 small pictures of single words cut out of photographs, with the word
written under each. Nobody here labelled anything.

    python scripts/measure_text.py PARQUET [--sample N] [--det MODEL.onnx] [--rec MODEL.onnx]

--det and --rec replace the models of the engine, to compare them on the same words.

This measures the *reading* of a word that has already been found, not the
finding of text in a whole photograph: it is the easier half. What a search
needs is that a word read is a word that can be typed, so a reading counts as
right when it is the same word ignoring case and accents, the way the index
compares them.
"""

import io
import json
import random
import sys
import time
import unicodedata

import numpy as np
import onnxruntime as ort
import pyarrow.parquet as pq
from PIL import Image, ImageOps

ort.disable_telemetry_events()


def fold(text):
    text = unicodedata.normalize("NFKD", text.lower())
    return "".join(c for c in text if c.isalnum())


def distance(a, b):
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


def main(argv):
    from rapidocr_onnxruntime import RapidOCR

    path = argv[0]
    sample = int(argv[argv.index("--sample") + 1]) if "--sample" in argv else None
    rows = pq.read_table(path).to_pylist()
    if sample and sample < len(rows):
        random.Random(1).shuffle(rows)
        rows = rows[:sample]
    options = {}
    for flag, key in (("--det", "det_model_path"), ("--rec", "rec_model_path")):
        if flag in argv:
            options[key] = argv[argv.index(flag) + 1]
    ocr = RapidOCR(**options)
    started = time.perf_counter()

    exact = close = silent = evaluated = 0
    errors = characters = 0
    for position, row in enumerate(rows):
        truth = fold(row["text"])
        if not truth:
            continue
        with Image.open(io.BytesIO(row["image"]["bytes"])) as opened:
            word = opened.convert("RGB")
        # A margin lets the detector see a word that fills its picture.
        word = ImageOps.expand(word, border=max(16, word.height // 2), fill=word.getpixel((1, 1)))
        result, _ = ocr(np.asarray(word)[:, :, ::-1])
        read = fold("".join(line[1] for line in result)) if result else ""
        evaluated += 1
        silent += not read
        exact += read == truth
        close += distance(read, truth) <= max(1, len(truth) // 5)
        errors += distance(read, truth)
        characters += len(truth)
        if (position + 1) % 500 == 0:
            print(f"  {position + 1} mots", file=sys.stderr, flush=True)

    print(json.dumps({
        "words": evaluated,
        "read_exactly": round(exact / evaluated, 3),
        "read_within_one_letter_in_five": round(close / evaluated, 3),
        "nothing_read": round(silent / evaluated, 3),
        "character_error_rate": round(errors / characters, 3),
        "seconds_per_word": round((time.perf_counter() - started) / evaluated, 3),
    }, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
