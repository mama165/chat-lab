"""Write the reference vectors of the indoor / outdoor judgement to a JSON file.

The Android test app needs only these three vectors, not the text model:
they are computed once here, with the same sentences ImageJudge uses.

    python scripts/export_judge_vectors.py MODELS/siglip2 OUTPUT.json
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from describe_library import ImageJudge  # noqa: E402


def main(argv):
    model_dir, output = argv[0], argv[1]
    judge = ImageJudge(model_dir)
    vectors = {
        "indoor": [float(x) for x in judge.indoor],
        "outdoor": [float(x) for x in judge.outdoor],
        "flat": [float(x) for x in judge.flat],
    }
    with open(output, "w", encoding="utf-8") as out:
        json.dump(vectors, out)
    print(f"{output}: {len(vectors['indoor'])} dimensions par vecteur", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
