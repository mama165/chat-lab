# What uses memory during a search

This document explains, from the Android app's code, what occupies memory when the user searches for a
photo, and why a search can crash the app. It describes the code as it is today. Figures are either
observed or calculated; anything still to be measured is marked as such.

## 1. The two memory spaces

An Android app has two memory spaces, counted differently.

- **The Java heap**: the objects created by the Kotlin code (lists, strings, records, hash tables). It has
  a fixed limit. On the test Pixel, the error message shows `target footprint 268435456`, so the limit is
  **256 MB**. When the heap is full, Android throws an `OutOfMemoryError` and stops the app.
- **Native memory**: the C++ libraries and the data they manage. ONNX Runtime, which runs the models,
  stores its data there. This memory is not counted in the 256 MB. It has its own, larger limit, watched
  by `WorkerGuard` during the analysis.

A file read with `MappedByteBuffer` (memory mapping) lives in native memory, not in the heap. This is what
`ModelFiles.session` does for the text model.

**The search crash happens in the Java heap.** Native memory can be full without crashing the app in the
same way.

## 2. What stays in memory all the time

While the Search screen is open, the library is in memory as decoded records.

- The library is the file `library.json`: a JSON list with one record per analysed photo.
- `Library.load` reads the file, turns it into JSON objects, then creates one `LibraryEntry` per photo.
  Each entry keeps its fields (name, path, date, text read, flags) and its vector, a `FloatArray` of 768
  numbers (about 3 KB per photo).
- `MainScreen` keeps the result in a `VersionedCache`. The library is therefore **not re-read at each
  search**: it is re-read when `dataVersion` changes, that is after an analysis or a change of folders
  (`dataVersion++` in `MainScreen.kt`).

So what stays in memory during a search is mostly the decoded library. Its size grows with the number of
analysed photos.

## 3. Who else reads the library

The library is read in several places, and each read can create its own copy in memory.

| Who | How | What stays in memory |
|---|---|---|
| Search screen (`Library.load`) | Reads the whole file, creates the entries | The entries, for the whole session |
| Analysis (`FullAnalysis.run`) | `LibraryStore.read` then `readKnown`: reads the whole file at the start of each analysis | The JSON objects of each entry, for the whole analysis |
| Folders page (`overview`) | Same full read as the analysis | The objects, while the page is displayed |
| "Add a folder" page (`KnownSummary`) | Reads the file **one entry at a time**, keeps only size, date and type | Almost nothing: it is the only economical read |

So **the library can be in the heap several times at once**, if an analysis runs while the user browses the
folders or searches. The analysis is the heaviest read, because it keeps the objects for its whole duration.

## 4. Step by step, a search

Each step is a function in the code. The numbers follow the order in which things happen.

**Step 1: typing.** `SearchField` updates `state.query`. The letters appear at once.

**Step 2: the pause.** `SearchScreen` waits 400 ms after the last letter (`LaunchedEffect(query)`). Nothing
is computed during that time. The text results are also delayed, by 250 ms.

**Step 3: the vector of the sentence.** `TextModels.vectorFor` (on the `Dispatchers.Default` thread):
- on the **first call** of the app's life, `TextModels.get` creates a `TextModel`;
- `TextModel` opens the text model (memory-mapped, so in native memory);
- it creates a `SigLipTokenizer`: the file `tokenizer.json` is read as text, then turned into hash tables
  (`vocab`, `ranks`, `cache`). **This is the dictionary.** It holds tens of thousands of word pieces, and
  each entry is a Java object;
- `encode` turns the sentence into pieces, then into 768 numbers (the vector of the sentence).

Important point: the `TextModel` is cached only if its creation **succeeds**. If it fails (for instance
for lack of memory), the next letter typed starts the creation again. A series of keystrokes can therefore
trigger the same peak several times.

**Step 4: ranking.** `Library.rankDescription` goes through all the entries:
- `filter { inPlace }` creates a list;
- `filter { faces }` may create another one;
- `mapNotNull { Match(entry, dot(query, vector)) }` creates one `Match` object per photo;
- `sortedByDescending` creates one more, sorted, list.

These lists are temporary. For 2,825 photos, each `Match` weighs a few dozen bytes: the ranking therefore
amounts to **a few hundred KB**, not tens of MB. (Estimate, to be checked with the profiler.)

**Step 5: results.** `SearchResults.compute` (on the `Default` thread) builds the rows shown.
`Removals.sourcesFor` reads the file of photos removed from searches, also as JSON.

**Step 6: display.** `GridThumb` decodes thumbnails (`decodeThumbnail`) on the IO thread. On recent
Android versions the pixels of an image are not in the Java heap; this still has to be confirmed with the
profiler.

## 5. The crash of that evening

The crash trace (`FATAL EXCEPTION: main`, `OutOfMemoryError`) has these characteristics:

- the error is an `OutOfMemoryError`: a shortage of **Java heap**, not a native crash;
- the last allocation that fails is the one of the tokenizer, in `SigLipTokenizer.<init>`;
- the stack goes back to `TextModels.vectorFor`, then to the search (`SearchScreen`);
- when the crash happened, about 157 KB were free out of 256 MB.

So the search triggered the loading of the dictionary while the heap was already almost full. What was
filling the heap is not established yet: the decoded library is the main candidate, and the copies made by
the analysis or the folders page still have to be checked.

## 6. What we do not know yet

- The **real size of the library** in memory (not the size of the file on disk, which we know: about 22 to
  28 MB for 2,825 photos).
- The **real size of the dictionary** of the tokenizer.
- The **copies that coexist** at the time of the crash: this depends on what was running (analysis, folders
  page, Search screen).
- The **size of the thumbnails** in memory.

## 7. How to measure

1. In Android Studio, open the **Memory Profiler** with the phone connected and the app running.
2. Note the Java memory before a search, then type a phrase. Take a **heap dump** right after the first
   result: it lists objects by size and by class.
3. To know what was present during a crash, keep the saved **Logcat** (`Save Logcat to File`), with the
   `FATAL EXCEPTION` lines and the stack.
4. The app's journal (`ChatLab/diagnostics`) already contains `searchRank` (duration, number of photos,
   number of results) and `searchTimeout`. It does not record memory use yet.

## 8. Options, not implemented

- **One shared read of the library.** Today the analysis, the folders page and the Search screen each read
  the file. A single copy, or an entry-by-entry read like `KnownSummary`, would remove the peaks that
  overlap.
- **Vectors stored in one block of numbers**, instead of one object per photo: 2,825 photos × 768 × 4 bytes
  ≈ 8.7 MB, against several times more with objects.
- **A compact dictionary** for the tokenizer, or a read that keeps only what is needed.
- **A clean error message** on `OutOfMemoryError` while loading the model, instead of a sudden stop. This
  is only a safety net, not a fix.

These options should be chosen after measuring, to target what really weighs the most.
