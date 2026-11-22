package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Découpeur BPE de SigLIP 2, le même que scripts/bpe_pure.py : il lit tokenizer.json
 * et produit les mêmes identifiants, complétés à [maxLength] et finis par <eos>.
 */
class SigLipTokenizer(json: String, private val maxLength: Int = 64) {
    private val vocab = HashMap<String, Int>()
    private val ranks = HashMap<String, Int>()
    private val eos: Int
    private val pad: Int
    private val cache = HashMap<String, IntArray>()

    init {
        val model = JSONObject(json).getJSONObject("model")
        val vocabJson = model.getJSONObject("vocab")
        val keys = vocabJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            vocab[key] = vocabJson.getInt(key)
        }
        val merges = model.getJSONArray("merges")
        for (index in 0 until merges.length()) {
            val item = merges.get(index)
            // A merge is either "a b" or a pair ["a", "b"], depending on the file.
            val pair = if (item is JSONArray) "${item.getString(0)} ${item.getString(1)}" else item as String
            ranks[pair] = index
        }
        eos = vocab.getValue("<eos>")
        pad = vocab.getValue("<pad>")
    }

    fun encode(text: String): IntArray {
        val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFC)
        val pieces = normalized.split(" ")
        val ids = ArrayList<Int>()
        for ((index, piece) in pieces.withIndex()) {
            val word = if (index > 0 || normalized.startsWith(" ")) "▁$piece" else piece
            if (word.isNotEmpty()) {
                for (id in wordIds(word)) ids.add(id)
            }
        }
        val kept = ids.take(maxLength - 1)
        val result = IntArray(maxLength) { pad }
        for ((index, id) in kept.withIndex()) result[index] = id
        result[kept.size] = eos
        return result
    }

    private fun wordIds(word: String): IntArray {
        cache[word]?.let { return it }
        val ids = ArrayList<Int>()
        for (symbol in merge(symbols(word))) {
            val id = vocab[symbol]
            if (id != null) {
                ids.add(id)
            } else {
                // Unknown symbol: fall back on its bytes, as the model expects.
                for (byte in symbol.toByteArray(Charsets.UTF_8)) {
                    ids.add(vocab["<0x%02X>".format(byte.toInt() and 0xFF)] ?: 0)
                }
            }
        }
        val result = ids.toIntArray()
        cache[word] = result
        return result
    }

    /** Une chaîne découpée en caractères (points de code Unicode). */
    private fun symbols(word: String): MutableList<String> {
        val list = ArrayList<String>()
        var index = 0
        while (index < word.length) {
            val codePoint = word.codePointAt(index)
            val count = Character.charCount(codePoint)
            list.add(word.substring(index, index + count))
            index += count
        }
        return list
    }

    /** Fusionne les paires de rang le plus bas, jusqu'à ce qu'il n'y en ait plus. */
    private fun merge(symbols: MutableList<String>): MutableList<String> {
        while (symbols.size > 1) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            for (index in 0 until symbols.size - 1) {
                val rank = ranks["${symbols[index]} ${symbols[index + 1]}"] ?: continue
                if (rank < bestRank) {
                    best = index
                    bestRank = rank
                }
            }
            if (best < 0) break
            symbols[best] = symbols[best] + symbols[best + 1]
            symbols.removeAt(best + 1)
        }
        return symbols
    }
}
