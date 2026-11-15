"""Serve the text side of SigLIP 2 to the search, over standard input and output.

The search (catalog.ProcessEncoder) writes one JSON line per description and
reads one JSON line back. No network is involved.

    → {"text": "une rose rouge"}
    ← {"vector": [0.01, -0.02, ...]}

    python scripts/encode_text.py MODELS/siglip2 [--full]

By default the 8-bit text model is used (283 MB); --full uses the 32-bit one
(1.1 GB). The pictures must have been described with the image side of the
same model: see describe_library.py.
"""

import json
import os
import sys

import numpy as np
import onnxruntime as ort

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bpe_pure import PureTokenizer  # noqa: E402

ort.disable_telemetry_events()


class TextEncoder:
    """The text side of SigLIP 2: a description becomes a vector."""

    def __init__(self, model_dir, full=False, name=None):
        name = name or ("text_model.onnx" if full else "text_model_quantized.onnx")
        self.session = ort.InferenceSession(os.path.join(model_dir, "onnx", name), providers=["CPUExecutionProvider"])
        # Pure Python tokenizer: the same ids as the `tokenizers` package
        # (checked on the public descriptions), and no package to build on Termux.
        self.tokenizer = PureTokenizer(os.path.join(model_dir, "tokenizer.json"), max_length=64)

    def encode_many(self, texts):
        ids = np.array([self.tokenizer.encode(text) for text in texts], dtype=np.int64)
        outputs = self.session.run(["pooler_output"], {"input_ids": ids})[0]
        return outputs / np.linalg.norm(outputs, axis=1, keepdims=True)

    def encode(self, text):
        return self.encode_many([text])[0]


def main(argv):
    if not argv or argv[0].startswith("-"):
        print(__doc__, file=sys.stderr)
        return 2
    encoder = TextEncoder(argv[0], full="--full" in argv[1:])
    for line in sys.stdin:
        try:
            text = json.loads(line)["text"]
            if not text.strip():
                raise ValueError("empty text")
            vector = encoder.encode(text)
            reply = {"vector": [round(float(x), 6) for x in vector]}
        except Exception as error:  # one bad request must not stop the server
            reply = {"error": f"{type(error).__name__}: {error}"}
        print(json.dumps(reply), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
