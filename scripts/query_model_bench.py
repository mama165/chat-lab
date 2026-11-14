"""Run a small language model over the reference sentences and write plans.

The output is JSON Lines, read by the benchmark of domain/query:

    QUERY_BENCH=<output> go test ./domain/query/ -run TestBenchmark -v

Usage:
    python scripts/query_model_bench.py MODEL.gguf SENTENCES.json OUTPUT.jsonl

The model is asked for one plan per sentence, in the format of domain/query.
The format is enforced by a JSON schema while the model writes, so the output
is always well-formed; whether the plan is right is decided by the benchmark.
"""

import json
import sys
import time

from llama_cpp import Llama

TODAY = "2026-11-14"

ORIGIN = {
    "type": "object",
    "properties": {"text": {"type": "string"}},
    "required": ["text"],
}

SCHEMA = {
    "type": "object",
    "properties": {
        "kinds": {"type": "array", "items": {"type": "string", "enum": ["image", "video", "pdf", "audio"]}},
        "kinds_origin": ORIGIN,
        "visual": {
            "type": "object",
            "properties": {"description": {"type": "string"}, "origin": ORIGIN},
            "required": ["description", "origin"],
        },
        "text": {
            "type": "object",
            "properties": {
                "terms": {
                    "type": "array",
                    "items": {"type": "object", "properties": {"value": {"type": "string"}}, "required": ["value"]},
                },
                "scope": {"type": "string", "enum": ["any", "written"]},
                "origin": ORIGIN,
            },
            "required": ["terms", "scope", "origin"],
        },
        "date": {
            "type": "object",
            "properties": {
                "from": {"type": "string"},
                "to": {"type": "string"},
                "label": {"type": "string"},
                "origin": ORIGIN,
            },
            "required": ["from", "to", "label", "origin"],
        },
        "features": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"name": {"type": "string", "enum": ["code", "portrait", "signature"]}, "origin": ORIGIN},
                "required": ["name", "origin"],
            },
        },
        "sort": {"type": "string", "enum": ["relevance", "newest", "oldest", "largest", "smallest"]},
        "cardinality": {"type": "string", "enum": ["one", "many", "all"]},
    },
}

EXAMPLE_JSON = """{"kinds": ["pdf"], "kinds_origin": {"text": "PDF"},
 "date": {"from": "2026-08-14T00:00:00Z", "to": "2026-11-15T00:00:00Z", "label": "ces 3 derniers mois", "origin": {"text": "ces 3 derniers mois"}},
 "text": {"terms": [{"value": "facture"}, {"value": "électricité"}], "scope": "any", "origin": {"text": "facture d'électricité"}},
 "sort": "relevance", "cardinality": "many"}"""

INSTRUCTIONS = f"""Tu transformes une phrase de recherche de fichiers personnels en plan JSON.
Aujourd'hui nous sommes le {TODAY}.
Règles :
- N'ajoute jamais un critère que la phrase n'exprime pas.
- Chaque critère donne dans "origin.text" le passage exact de la phrase qui le justifie.
- kinds : image pour photo, video pour vidéo, pdf pour PDF ou document, audio pour mémo vocal.
- date : from inclus, to exclu, au format AAAA-MM-JJT00:00:00Z (exemple : 2026-06-18T00:00:00Z). "il y a un mois" couvre environ 20 à 45 jours avant aujourd'hui.
- visual : ce que la phrase décrit à voir dans une image, tel quel.
- text : les mots à chercher dans le texte d'un fichier.
- features : code pour un QR code, portrait pour un portrait, signature pour une signature.
- Réponds uniquement avec le JSON.

Exemple.
Phrase : Trouve-moi le PDF de la facture d'électricité de ces 3 derniers mois.
JSON :
""" + EXAMPLE_JSON


def main():
    model_path, sentences_path, output_path = sys.argv[1:4]
    with open(sentences_path, encoding="utf-8") as f:
        sentences = [item["sentence"] for item in json.load(f)]

    llm = Llama(model_path=model_path, n_ctx=2048, n_threads=4, verbose=False)
    with open(output_path, "w", encoding="utf-8") as out:
        for sentence in sentences:
            started = time.perf_counter()
            result = llm.create_chat_completion(
                messages=[
                    {"role": "system", "content": INSTRUCTIONS},
                    {"role": "user", "content": sentence + " /no_think"},
                ],
                response_format={"type": "json_object", "schema": SCHEMA},
                temperature=0.0,
                max_tokens=700,
            )
            seconds = time.perf_counter() - started
            content = result["choices"][0]["message"]["content"]
            try:
                plan = json.loads(content)
            except json.JSONDecodeError:
                plan = None
            record = {"sentence": sentence, "seconds": seconds}
            if plan is None:
                record["plan_error"] = content[:200]
                record["plan"] = {}
            else:
                record["plan"] = plan
            out.write(json.dumps(record, ensure_ascii=False) + "\n")
            print(f"{seconds:5.1f}s  {sentence}", flush=True)


if __name__ == "__main__":
    main()
