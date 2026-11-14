# What a picture shows: the vision model

Nothing about a picture is labelled by hand. When a folder is read, each picture is described by models that run on the machine; when a question is asked, the same vision model reads the question.

| Read from a picture | With | Licence | Size |
| :--- | :--- | :--- | :--- |
| What it shows (a vector) | SigLIP 2 base, 224 pixels, image side | Apache 2.0 | 372 MB |
| Indoors or outdoors | The same model, compared with two sentences | — | — |
| Number of faces | YuNet (OpenCV) | Apache 2.0 | 0.2 MB |
| Text visible in it | RapidOCR | Apache 2.0 | in the package |
| QR codes | OpenCV | Apache 2.0 | in the package |
| Date taken, GPS position | The picture's EXIF data | — | — |

| Read from a question | With | Size |
| :--- | :--- | :--- |
| The vector of a description | SigLIP 2, text side | 1.1 GB, or 443 MB with no measurable loss (4 bits, 16-bit activations) |

`scripts/describe_library.py` does the first table for a folder; `scripts/encode_text.py` serves the second to the search. Models are downloaded once from Hugging Face and are not stored in the repository. Everything runs locally; the telemetry of the inference runtime is switched off.

## Why SigLIP 2

It is the one candidate that is multilingual, permissively licensed, and already published in a format that runs outside Python. The model was chosen on those grounds, then measured, rather than chosen on a handful of pictures.

## The measure

XM3600 is a public set of 3 600 photographs taken all over the world, each described in French by people: 8 562 descriptions. For each description, the photograph it was written for is searched among all 3 600. Nobody here labelled anything.

| Text side of the model | Size | First result | In the first 5 | In the first 10 | Time per description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 32 bits | 1.1 GB | 67.2 % | 88.8 % | 93.7 % | 30 ms |
| 16 bits | 565 MB | 67.2 % | 88.8 % | 93.7 % | 68 ms |
| 4 bits, 16-bit activations | 443 MB | 67.2 % | 88.8 % | 93.7 % | 50 ms |
| 4 bits | 843 MB | 67.2 % | 88.7 % | 93.7 % | 30 ms |
| 8 bits (`text_model_quantized`) | 283 MB | 50.8 % | 74.8 % | 82.7 % | 13 ms |

The image side was measured in 32 bits only: 0.1 s per picture. Times are on a 4-core desktop CPU, descriptions in batches of 32; each line is one run, and the times of a single run are rough.

**Only one of the five variants is a bad saving.** The 8-bit text model is four times smaller than the 32-bit one and loses 16 points on the first result. The four others lose nothing measurable, down to 443 MB. Size alone does not predict the loss: the 4-bit variants are far better than the 8-bit one, so each variant has to be measured, not assumed.

Which one to keep is a question for the phone: speed on this CPU says nothing of a phone, which has native 16-bit arithmetic and a different memory budget.

To run it again:

```bash
python scripts/measure_retrieval.py MODELS/siglip2 XM3600_DIR CACHE_DIR --full
```

`XM3600_DIR` holds the French part of the Hugging Face dataset `mteb/XM3600T2IRetrieval` (CC BY-SA 4.0).

## When is a picture a match

SigLIP 2 gives small similarities: around 0.13 between a description and its photograph. Two thresholds, read off the measure, decide what counts as a match:

- **Floor, 0.08.** 95 % of the descriptions are at 0.084 or more from their own photograph. Below, a picture is very unlikely to show what was described: asked for a dog, a library without one answers "nothing".
- **Margin, 0.04.** For 95 % of the descriptions, their own photograph is within 0.036 of the closest one. A picture within the margin of the best may well be the right one, and is counted as a match.

Pictures past the margin are still listed, marked as less close and not counted.

## Facts are checked, descriptions are compared

"La photo prise en intérieur avec deux personnes" describes nothing to look at: it names two facts. A description that vague scores low against every picture. So the facts a question can name are read when the picture is described, and checked when it is searched:

- **Indoors or outdoors.** A picture judged the other way is dropped. A picture left unjudged (a poster, a studio backdrop too close to call) is kept, after the others, and says so.
- **A number of people** ("5 personnes", "deux ou trois personnes", "sans personne"). Checked against the faces counted. One face off is kept after the exact ones, because a face can be missed.

## What was checked, and on how much

| What | Checked on | Result |
| :--- | :--- | :--- |
| Description → picture, in French | 8 562 public descriptions, 3 600 photographs | Table above |
| Faces | 9 pictures holding 16 faces | 16 found, none on the 3 pictures without people |
| Indoors or outdoors | 9 photographs | 6 right, 3 studio pictures left unjudged, none wrong |
| Text in pictures | 6 pictures | Dark text on light: read. Light text on colour: read after contrast and inversion. Heavily styled lettering: read in part. |

The first line is a measure. The three others are checks on a handful of pictures: they show that the parts work together, not how good they are. Faces, scenes and text each have public benchmarks that were not run here.

## Not measured

- Anything on a phone: speed, memory, heat.
- The image side in 16 or 8 bits.
- Videos: the model is the same, one vector per sampled frame, but no video has been read.
- Small faces, profiles, low light; people seen from behind are not faces.
- Short questions. The benchmark's descriptions are full sentences; a question of two words scores lower, and the floor may be too high for some.
