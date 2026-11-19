package com.example.chatlab

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.sqrt

/**
 * Découpeur BPE de SigLIP 2, le même que scripts/bpe_pure.py : il lit tokenizer.json
 * et produit les mêmes identifiants, complétés à [maxLength] et finis par <eos>.
 */
class SigLipTokenizer(file: File, private val maxLength: Int = 64) {
    private val vocab = HashMap<String, Int>()
    private val ranks = HashMap<String, Int>()
    private val eos: Int
    private val pad: Int
    private val cache = HashMap<String, IntArray>()

    init {
        val model = JSONObject(file.readText(Charsets.UTF_8)).getJSONObject("model")
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

/** Le côté texte de SigLIP 2 : une description devient un vecteur normalisé. */
class TextModel(private val env: OrtEnvironment, models: File) : AutoCloseable {
    private val session: OrtSession
    private val tokenizer: SigLipTokenizer

    init {
        val file = listOf("text_model_q4f16.onnx", "text_model_quantized.onnx")
            .map { File(models, it) }
            .firstOrNull { it.exists() }
            ?: error("modèle de texte absent : copiez text_model_q4f16.onnx dans chatlab/models, puis lancez une analyse")
        val tokenizerFile = File(models, "tokenizer.json")
        if (!tokenizerFile.exists()) error("tokenizer.json absent de chatlab/models")
        session = env.createSession(file.path, OrtSession.SessionOptions())
        tokenizer = SigLipTokenizer(tokenizerFile)
    }

    fun encode(text: String): FloatArray {
        val ids = tokenizer.encode(text)
        val input = LongArray(ids.size) { ids[it].toLong() }
        OnnxTensor.createTensor(env, LongBuffer.wrap(input), longArrayOf(1, ids.size.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = (result.get("pooler_output").get().value as Array<FloatArray>)[0]
                return normalize(output)
            }
        }
    }

    fun ids(text: String): IntArray = tokenizer.encode(text)

    private fun normalize(v: FloatArray): FloatArray {
        val length = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return FloatArray(v.size) { v[it] / length }
    }

    override fun close() {
        session.close()
    }
}

/** Un seul modèle de texte chargé, partagé par la recherche et le contrôle. */
object TextModels {
    private var loaded: TextModel? = null

    @Synchronized
    fun get(context: Context): TextModel {
        loaded?.let { return it }
        val model = TextModel(OrtEnvironment.getEnvironment(), File(context.filesDir, "models"))
        loaded = model
        return model
    }

    /** Ligne de contrôle pour le compte rendu : identifiants et premières valeurs, à comparer à l'ordinateur. */
    @Synchronized
    fun control(context: Context): String {
        val model = get(context)
        val sentence = "une rose rouge dans un jardin"
        val ids = model.ids(sentence).take(8).joinToString(", ")
        val vector = model.encode(sentence).take(5).joinToString(" ; ") { "%.6f".format(it) }
        return "contrôle texte : ids [$ids] ; vecteur [$vector]"
    }
}
