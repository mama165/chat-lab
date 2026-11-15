"""Tokenizer of SigLIP 2, in pure Python.

The model's tokenizer is a byte-pair encoder (BPE) from the `tokenizers`
package, which cannot be built on Termux. This module reads the same
tokenizer.json and does the same work, in plain Python. It has no dependency
beyond the standard library.

    tokenizer = PureTokenizer("MODELS/siglip2/tokenizer.json")
    tokenizer.encode("une rose rouge")   # -> list of token ids, ends with <eos>

Its output is checked, token for token, against the `tokenizers` package on
the public descriptions (see TestPureTokenizer in scripts/check_bpe.py).
"""

import json
import unicodedata


class PureTokenizer:
    def __init__(self, path, max_length=64):
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        model = data["model"]
        self.vocab = model["vocab"]
        self.ranks = {}
        for rank, merge in enumerate(model["merges"]):
            pair = tuple(merge.split(" ", 1)) if isinstance(merge, str) else tuple(merge)
            self.ranks[pair] = rank
        self.byte_fallback = model.get("byte_fallback", False)
        self.unk = model.get("unk_id")
        self.eos = self.vocab["<eos>"]
        self.pad = self.vocab["<pad>"]
        self.max_length = max_length
        self.cache = {}

    def _bytes_token(self, byte):
        return self.vocab.get(f"<0x{byte:02X}>", self.unk)

    def _merge(self, symbols):
        """Apply merges by rank until no pair is mergeable."""
        while len(symbols) > 1:
            best, best_rank = None, None
            for i in range(len(symbols) - 1):
                rank = self.ranks.get((symbols[i], symbols[i + 1]))
                if rank is not None and (best_rank is None or rank < best_rank):
                    best, best_rank = i, rank
            if best is None:
                break
            symbols[best:best + 2] = [symbols[best] + symbols[best + 1]]
        return symbols

    def _word(self, word):
        if word in self.cache:
            return self.cache[word]
        ids = []
        for symbol in self._merge(list(word)):
            if symbol in self.vocab:
                ids.append(self.vocab[symbol])
            else:
                # Unknown symbol: fall back on its bytes, as the model expects.
                for byte in symbol.encode("utf-8"):
                    ids.append(self._bytes_token(byte))
        if len(self.cache) < 100_000:
            self.cache[word] = ids
        return ids

    def encode(self, text):
        # Same normalisation as the tokenizer file: lower case, spaces become "▁".
        text = unicodedata.normalize("NFC", text.lower())
        pieces = text.split(" ")
        ids = []
        for index, piece in enumerate(pieces):
            word = ("▁" + piece) if index > 0 or text.startswith(" ") else piece
            if word:
                ids.extend(self._word(word))
        ids = ids[: self.max_length - 1]
        ids.append(self.eos)
        ids.extend([self.pad] * (self.max_length - len(ids)))
        return ids
