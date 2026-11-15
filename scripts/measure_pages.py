"""Measure how many of the words of a whole page the text reader finds.

Two public benchmarks, with the text of every page known:

  FUNSD     scanned forms, every word marked by hand (Hugging Face dataset
            nielsr/funsd, test part, 50 forms).
  WebSight  screenshots of web pages with the HTML they were made from
            (HuggingFaceM4/WebSight, licence CC BY 4.0). The visible text of
            the page is taken from the HTML.

    python scripts/measure_pages.py funsd   PARQUET [--sample N]
    python scripts/measure_pages.py websight PARQUET [--sample N]

--passes N reads each page N ways instead of three, to see what the extra readings cost and bring.

This is the question a search asks: of the words on the page, how many can be
typed in the search box and found? The page goes through exactly the reader
the catalog uses (TextReader in describe_library.py). A word counts as found
when it is among the words read, ignoring case and accents, the way the index
compares them. Only words of three letters or more are counted: short words
are not what anyone searches for.
"""

import html.parser
import io
import json
import os
import random
import sys
import time
import unicodedata

import pyarrow.parquet as pq
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from describe_library import TextReader  # noqa: E402


def fold(text):
    text = unicodedata.normalize("NFKD", text.lower())
    return "".join(c for c in text if c.isalnum())


def words_of(text):
    """The distinct words of three letters or more, folded."""
    found = set()
    for raw in text.replace("\n", " ").split():
        word = fold(raw)
        if len(word) >= 3 and not word.isdigit():
            found.add(word)
    return found


class VisibleText(html.parser.HTMLParser):
    SKIPPED = {"style", "script", "title", "head"}

    def __init__(self):
        super().__init__()
        self.depth, self.parts = 0, []

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIPPED:
            self.depth += 1

    def handle_endtag(self, tag):
        if tag in self.SKIPPED and self.depth:
            self.depth -= 1

    def handle_data(self, data):
        if not self.depth:
            self.parts.append(data)


def truth(kind, row):
    if kind == "funsd":
        return words_of(" ".join(row["words"]))
    parser = VisibleText()
    parser.feed(row["text"])
    return words_of(" ".join(parser.parts))


def main(argv):
    kind, path = argv[0], argv[1]
    sample = int(argv[argv.index("--sample") + 1]) if "--sample" in argv else None
    rows = pq.read_table(path).to_pylist() if kind == "funsd" else None
    if rows is None:
        table = pq.ParquetFile(path).read_row_group(0)
        rows = table.to_pylist()
    if sample and sample < len(rows):
        random.Random(1).shuffle(rows)
        rows = rows[:sample]
    passes = int(argv[argv.index("--passes") + 1]) if "--passes" in argv else 3
    reader = TextReader(passes=passes)

    found = expected = 0
    per_page = []
    started = time.perf_counter()
    for position, row in enumerate(rows):
        wanted = truth(kind, row)
        if len(wanted) < 3:
            continue
        with Image.open(io.BytesIO(row["image"]["bytes"])) as opened:
            read = words_of(reader.read(opened))
        hit = len(wanted & read)
        found += hit
        expected += len(wanted)
        per_page.append(hit / len(wanted))
        if (position + 1) % 20 == 0:
            print(f"  {position + 1} pages", file=sys.stderr, flush=True)

    per_page.sort()
    print(json.dumps({
        "pages": len(per_page),
        "words_to_find": expected,
        "words_found": round(found / expected, 3),
        "median_page": round(per_page[len(per_page) // 2], 3),
        "worst_tenth_of_pages_below": round(per_page[len(per_page) // 10], 3),
        "pages_where_nine_words_in_ten_are_found": round(sum(p >= 0.9 for p in per_page) / len(per_page), 3),
        "seconds_per_page": round((time.perf_counter() - started) / len(per_page), 2),
    }, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
