"""Export the XM3600 benchmark in the formats the Go tests read.

    python scripts/export_xm3600.py XM3600_DIR CACHE_DIR OUTPUT_DIR

Writes OUTPUT_DIR/library.json (one entry per photograph, with the vector made
by measure_retrieval.py) and OUTPUT_DIR/queries.json (the French descriptions
and the photograph each was written for). The pictures themselves are not
copied: only their vectors.
"""

import json
import os
import sys

import numpy as np
import pyarrow.parquet as pq


def table(directory, name):
    return pq.read_table(os.path.join(directory, name, "test-00000-of-00001.parquet"))


def main(argv):
    data_dir, cache_dir, output = argv[:3]
    os.makedirs(output, exist_ok=True)
    cached = np.load(os.path.join(cache_dir, "xm3600_image_vectors.npz"), allow_pickle=False)
    ids, vectors = list(cached["ids"]), cached["vectors"]
    library = [
        {"id": str(i), "path": "/photos/" + str(i) + ".jpg", "name": str(i) + ".jpg", "kind": "image",
         "added_at": "2026-06-01T10:00:00Z", "vectors": [[round(float(x), 6) for x in v]]}
        for i, v in zip(ids, vectors)
    ]
    wanted = {r["query-id"]: r["corpus-id"] for r in table(data_dir, "fr-qrels").to_pylist()}
    queries = [{"id": q["id"], "text": q["text"], "target": wanted[q["id"]]} for q in table(data_dir, "fr-queries").to_pylist()]
    with open(os.path.join(output, "library.json"), "w", encoding="utf-8") as f:
        json.dump(library, f)
    with open(os.path.join(output, "queries.json"), "w", encoding="utf-8") as f:
        json.dump(queries, f, ensure_ascii=False)
    print(f"{len(library)} photographies, {len(queries)} descriptions", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
