"""Check scripts/bpe_pure.py against the reference tokenizer, on real descriptions.

    python scripts/check_bpe.py TOKENIZER_JSON QUERIES_JSON [--sample N]

The reference is the `tokenizers` package (available on the computer). The
descriptions are the 8 562 French queries of XM3600 exported by
export_xm3600.py. Every description must give exactly the same token ids.
"""

import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bpe_pure import PureTokenizer  # noqa: E402


def main(argv):
    from tokenizers import Tokenizer

    path, queries_path = argv[0], argv[1]
    sample = int(argv[argv.index("--sample") + 1]) if "--sample" in argv else None
    texts = [q["text"] for q in json.load(open(queries_path, encoding="utf-8"))]
    if sample and sample < len(texts):
        random.Random(1).shuffle(texts)
        texts = texts[:sample]

    reference = Tokenizer.from_file(path)
    reference.enable_truncation(max_length=64)
    reference.enable_padding(length=64, pad_id=0)
    pure = PureTokenizer(path)

    differing = []
    for text in texts:
        expected = reference.encode(text.lower()).ids
        got = pure.encode(text)
        if expected != got:
            differing.append((text, expected[:12], got[:12]))
    print(f"{len(texts)} descriptions, {len(differing)} differ")
    for text, expected, got in differing[:5]:
        print(f"  {text!r}\n    reference {expected}\n    pure      {got}")
    return 0 if not differing else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
