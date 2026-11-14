"""Ask a small language model which free words of a sentence are noise.

The rules of domain/query read everything exact in a sentence (kinds, dates,
places, counts). What they cannot judge is whether a leftover word describes
what is searched or is just conversation: "absolument", "je crois que".
This script gives the model that one question, and nothing else: it can only
name words to drop, among the words the rules left. It cannot add a criterion.

Usage:
    go build -o /tmp/query ./cmd/query
    python scripts/query_noise_bench.py MODEL.gguf SENTENCES.json OUTPUT.jsonl /tmp/query

The output is read by the benchmark of domain/query:
    QUERY_BENCH=OUTPUT.jsonl QUERY_BENCH_SET=testdata/SENTENCES.json \\
        go test ./domain/query/ -run TestBenchmark -v
"""

import json
import subprocess
import sys
import time

from llama_cpp import Llama

TODAY = "2026-11-14"

INSTRUCTIONS = """Tu aides un moteur de recherche de photos, de vidéos et de documents personnels.
On te donne une demande et la liste des mots que le moteur en a retenus.
Indique les mots de la liste qui ne décrivent PAS le contenu recherché :
formules de politesse, verbes de la demande (chercher, revoir, montrer, ressortir, avoir besoin),
hésitations, remarques de la personne sur elle-même (je crois, je ne sais plus, il me semble).
Garde tous les mots qui décrivent ce qu'on voit sur une image ou ce qui est écrit dans un document.
Dans le doute, garde le mot.
Réponds en JSON : {"bruit": [les mots à retirer]}."""

# The examples share no sentence with any test set.
EXAMPLES = [
    ("Dis-moi, tu saurais me retrouver la photo du gâteau au chocolat ?",
     ["dis", "saurais", "gâteau", "chocolat"], ["dis", "saurais"]),
    ("Il me semble que j'avais un document sur le prêt immobilier.",
     ["semble", "avais", "prêt", "immobilier"], ["semble", "avais"]),
    ("Les vidéos du match de foot.", ["match", "foot"], []),
    ("Je voudrais bien revoir la photo du vieux vélo rouge.",
     ["bien", "revoir", "vieux", "vélo", "rouge"], ["bien", "revoir"]),
    ("Le contrat du garagiste, je ne me souviens plus de la date.",
     ["contrat", "garagiste", "souviens", "date"], ["souviens", "date"]),
]


def question(sentence, words):
    return f"Demande : {sentence}\nMots : {json.dumps(words, ensure_ascii=False)} /no_think"


def main():
    model_path, sentences_path, output_path, query_binary = sys.argv[1:5]
    with open(sentences_path, encoding="utf-8") as f:
        sentences = [item["sentence"] for item in json.load(f)]

    listed = subprocess.run(
        [query_binary, "-candidates", "-now", TODAY],
        input="\n".join(sentences), capture_output=True, text=True, check=True,
    )
    candidates = {}
    for line in listed.stdout.splitlines():
        item = json.loads(line)
        candidates[item["sentence"]] = item["candidates"]

    llm = Llama(model_path=model_path, n_ctx=2048, n_threads=4, verbose=False)
    history = [{"role": "system", "content": INSTRUCTIONS}]
    for sentence, words, noise in EXAMPLES:
        history.append({"role": "user", "content": question(sentence, words)})
        history.append({"role": "assistant", "content": json.dumps({"bruit": noise}, ensure_ascii=False)})

    with open(output_path, "w", encoding="utf-8") as out:
        for sentence in sentences:
            words = list(dict.fromkeys(candidates.get(sentence, [])))
            drop, seconds = [], 0.0
            # A lone word is what the user is after: nothing to ask.
            if len(words) >= 2:
                schema = {
                    "type": "object",
                    "properties": {"bruit": {"type": "array", "items": {"type": "string", "enum": words}}},
                    "required": ["bruit"],
                }
                started = time.perf_counter()
                result = llm.create_chat_completion(
                    messages=history + [{"role": "user", "content": question(sentence, words)}],
                    response_format={"type": "json_object", "schema": schema},
                    temperature=0.0,
                    max_tokens=120,
                )
                seconds = time.perf_counter() - started
                try:
                    drop = json.loads(result["choices"][0]["message"]["content"]).get("bruit", [])
                except (json.JSONDecodeError, AttributeError):
                    drop = []
                drop = [w for w in dict.fromkeys(drop) if w in words]
            out.write(json.dumps(
                {"sentence": sentence, "rules": True, "drop": drop, "seconds": seconds}, ensure_ascii=False) + "\n")
            print(f"{seconds:5.1f}s  {words} -> retire {drop}", flush=True)


if __name__ == "__main__":
    main()
