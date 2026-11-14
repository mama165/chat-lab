"""Measure text-to-image search on a public benchmark, in French.

XM3600 holds 3 600 photographs from all over the world, each described in
French by people (8 562 descriptions). Nobody here labelled anything: for each
description, the script checks whether the photograph it was written for
comes back among the first results, when searched among all 3 600.

    python scripts/measure_retrieval.py MODELS/siglip2 XM3600_DIR CACHE_DIR [--full]

XM3600_DIR holds fr-corpus, fr-queries and fr-qrels, from the Hugging Face
dataset mteb/XM3600T2IRetrieval. Vectors are cached in CACHE_DIR.
"""

import io
import json
import os
import sys
import time

import numpy as np
import pyarrow.parquet as pq
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from describe_library import ImageEncoder  # noqa: E402
from encode_text import TextEncoder  # noqa: E402


def table(directory, name):
    return pq.read_table(os.path.join(directory, name, "test-00000-of-00001.parquet"))


def image_vectors(model_dir, data_dir, cache_dir):
    cache = os.path.join(cache_dir, "xm3600_image_vectors.npz")
    if os.path.exists(cache):
        loaded = np.load(cache, allow_pickle=False)
        return list(loaded["ids"]), loaded["vectors"], float(loaded["seconds"])
    encoder = ImageEncoder(model_dir)
    corpus = table(data_dir, "fr-corpus")
    ids, vectors = [], []
    started = time.perf_counter()
    for batch in corpus.to_batches(max_chunksize=64):
        for row in batch.to_pylist():
            with Image.open(io.BytesIO(row["image"]["bytes"])) as image:
                vectors.append(encoder.encode(image))
            ids.append(row["id"])
        print(f"  {len(ids)} images", file=sys.stderr, flush=True)
    seconds = (time.perf_counter() - started) / len(ids)
    vectors = np.stack(vectors)
    np.savez(cache, ids=np.array(ids), vectors=vectors, seconds=seconds)
    return ids, vectors, seconds


def main(argv):
    model_dir, data_dir, cache_dir = argv[:3]
    full = "--full" in argv[3:]
    os.makedirs(cache_dir, exist_ok=True)
    ids, images, image_seconds = image_vectors(model_dir, data_dir, cache_dir)
    position = {image_id: i for i, image_id in enumerate(ids)}

    queries = table(data_dir, "fr-queries").to_pylist()
    wanted = {row["query-id"]: row["corpus-id"] for row in table(data_dir, "fr-qrels").to_pylist()}
    encoder = TextEncoder(model_dir, full=full)

    ranks, own, best_other = [], [], []
    started = time.perf_counter()
    for start in range(0, len(queries), 32):
        chunk = queries[start:start + 32]
        vectors = encoder.encode_many([q["text"] for q in chunk])
        scores = vectors @ images.T
        for row, query in zip(scores, chunk):
            target = position[wanted[query["id"]]]
            ranks.append(int((row > row[target]).sum()) + 1)
            own.append(float(row[target]))
            others = np.delete(row, target)
            best_other.append(float(others.max()))
    text_seconds = (time.perf_counter() - started) / len(queries)

    ranks, own, best_other = np.array(ranks), np.array(own), np.array(best_other)
    report = {
        "model": "siglip2-base-patch16-224, text " + ("32 bits" if full else "8 bits"),
        "images": len(ids),
        "descriptions": len(queries),
        "recall_at_1": round(float((ranks <= 1).mean()), 4),
        "recall_at_5": round(float((ranks <= 5).mean()), 4),
        "recall_at_10": round(float((ranks <= 10).mean()), 4),
        "recall_at_20": round(float((ranks <= 20).mean()), 4),
        "median_rank": float(np.median(ranks)),
        "seconds_per_image": round(image_seconds, 3),
        "milliseconds_per_description_in_batches": round(1000 * text_seconds, 1),
        # How close a description is to its own photograph, and to the closest
        # other one: what a resemblance threshold has to separate.
        "similarity_to_own_image": {p: round(float(np.percentile(own, p)), 3) for p in (5, 25, 50, 75, 95)},
        "similarity_to_closest_other": {p: round(float(np.percentile(best_other, p)), 3) for p in (5, 25, 50, 75, 95)},
        "margin_below_best_that_keeps_own_image": {
            p: round(float(np.percentile(np.maximum(best_other, own) - own, p)), 3) for p in (50, 75, 90, 95)
        },
    }
    print(json.dumps(report, indent=1, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
