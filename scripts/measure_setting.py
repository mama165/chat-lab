"""Measure the indoor / outdoor judgement on a public benchmark.

IndoorOutdoorNet-20K holds 19 998 photographs labelled indoor or outdoor (half
each), licence Apache 2.0, from the Hugging Face dataset
prithivMLmods/IndoorOutdoorNet-20K. Nobody here labelled anything.

    python scripts/measure_setting.py MODELS/siglip2 PARQUET [--sample N]

Each photograph goes through exactly the judgement the catalog uses (see
ImageJudge in describe_library.py), which may answer "indoor", "outdoor" or
nothing at all when the picture is too ambiguous. Three numbers matter:

  - how often it answers at all;
  - when it answers, how often it is right;
  - how often it answers the wrong way: what makes a good photograph drop out
    of a search for "en intérieur".

A picture left unjudged is never dropped by a search, only listed after the
others: an abstention costs less than a mistake.
"""

import io
import json
import os
import random
import sys

import numpy as np
import pyarrow.parquet as pq
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from describe_library import ImageEncoder, ImageJudge, flatten  # noqa: E402

INDOOR, OUTDOOR = 0, 1


def main(argv):
    model_dir, path = argv[0], argv[1]
    sample = int(argv[argv.index("--sample") + 1]) if "--sample" in argv else None

    rows = pq.read_table(path).to_pylist()
    if sample and sample < len(rows):
        random.Random(1).shuffle(rows)
        rows = rows[:sample]
    encoder, judge = ImageEncoder(model_dir), ImageJudge(model_dir)

    # [truth][answer]: answer is 0 indoor, 1 outdoor, 2 nothing
    table = np.zeros((2, 3), dtype=int)
    unreadable = 0
    for position, row in enumerate(rows):
        try:
            with Image.open(io.BytesIO(row["image"]["bytes"])) as opened:
                vector = encoder.encode(flatten(opened))
        except Exception:
            unreadable += 1
            continue
        answer = judge.indoor_or_none(vector)
        table[row["label"]][2 if answer is None else (INDOOR if answer else OUTDOOR)] += 1
        if (position + 1) % 500 == 0:
            print(f"  {position + 1} photographies", file=sys.stderr, flush=True)

    total = int(table.sum())
    answered = int(table[:, :2].sum())
    right = int(table[INDOOR][INDOOR] + table[OUTDOOR][OUTDOOR])
    report = {
        "photographs": total,
        "unreadable": unreadable,
        "answers_at_all": round(answered / total, 3),
        "right_when_answering": round(right / answered, 3),
        "wrong_way": round((answered - right) / total, 3),
        "by_truth": {
            name: {
                "indoor": int(table[t][INDOOR]), "outdoor": int(table[t][OUTDOOR]), "unjudged": int(table[t][2]),
                "dropped_by_a_search_for_the_other": round(float(table[t][1 - t] / table[t].sum()), 3),
            }
            for t, name in ((INDOOR, "photographs that are indoor"), (OUTDOOR, "photographs that are outdoor"))
        },
    }
    print(json.dumps(report, indent=1, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
