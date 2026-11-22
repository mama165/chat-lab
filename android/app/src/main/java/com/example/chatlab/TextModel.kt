package com.example.chatlab

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.sqrt

/** Le côté texte de SigLIP 2 : une description devient un vecteur normalisé. */
class TextModel(private val env: OrtEnvironment, context: Context, models: DocumentFile) : AutoCloseable {
    private val session: OrtSession
    private val tokenizer: SigLipTokenizer

    init {
        // Lus directement dans chatlab/models, sans copie.
        val name = listOf("text_model_q4f16.onnx", "text_model_quantized.onnx")
            .firstOrNull { ModelFiles.exists(models, it) }
            ?: error("modèle de texte absent : copiez text_model_q4f16.onnx dans chatlab/models")
        if (!ModelFiles.exists(models, "tokenizer.json")) error("tokenizer.json absent de chatlab/models")
        session = ModelFiles.session(env, context, models, name)
        tokenizer = SigLipTokenizer(ModelFiles.text(context, models, "tokenizer.json"))
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
        val model = TextModel(OrtEnvironment.getEnvironment(), context, ModelFiles.folder(context))
        loaded = model
        return model
    }

    /**
     * Le vecteur d'une phrase : celui du fichier de test query_vectors.json s'il le fournit
     * (tests d'interface sur émulateur, sans le modèle de texte), sinon celui du modèle.
     */
    @Synchronized
    fun vectorFor(context: Context, text: String): FloatArray {
        val file = File(context.filesDir, "query_vectors.json")
        if (file.exists()) {
            val known = JSONObject(file.readText(Charsets.UTF_8))
            if (known.has(text)) {
                val array = known.getJSONArray(text)
                return FloatArray(array.length()) { array.getDouble(it).toFloat() }
            }
        }
        return get(context).encode(text)
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
