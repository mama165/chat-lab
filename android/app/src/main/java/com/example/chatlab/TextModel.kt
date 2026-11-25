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

/** The text side of SigLIP 2: a description becomes a normalised vector. */
class TextModel(private val env: OrtEnvironment, context: Context, models: DocumentFile) : AutoCloseable {
    private val session: OrtSession
    private val tokenizer: SigLipTokenizer

    init {
        // Read directly from chatlab/models, without copy.
        val name = listOf("text_model_q4f16.onnx", "text_model_quantized.onnx")
            .firstOrNull { ModelFiles.exists(models, it) }
            ?: error("modèle de texte absent : copiez text_model_q4f16.onnx dans chatlab/models")
        if (!ModelFiles.exists(models, "tokenizer.json")) error("tokenizer.json absent de chatlab/models")
        // Sentences are short and of varying length. The CPU arena and the memory-pattern cache keep
        // the native memory reserved after each call, so it grew from one search to the next (measured:
        // encodeMemory, the native floor rose from about 750 MB to 2.5 GB). Both are switched off here.
        val options = OrtSession.SessionOptions().apply {
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        }
        session = options.use { ModelFiles.session(env, context, models, name, it) }
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

/** A single text model loaded, shared by the search and the check. */
object TextModels {
    private const val RETRY_MS = 30_000L
    private var loaded: TextModel? = null
    private var failure: Exception? = null
    private var failedAt = 0L

    /**
     * The model is loaded once. After a failure, it is not loaded again at each keystroke: each load takes
     * native memory, and the retries piled up until the process was aborted. The next try waits RETRY_MS.
     */
    @Synchronized
    fun get(context: Context): TextModel {
        loaded?.let { return it }
        failure?.let { error ->
            if (System.currentTimeMillis() - failedAt < RETRY_MS) throw error
        }
        return try {
            val model = TextModel(OrtEnvironment.getEnvironment(), context, ModelFiles.folder(context))
            loaded = model
            failure = null
            Diagnostics.event(context, "modelLoad", JSONObject().put("ok", true))
            model
        } catch (error: Exception) {
            failure = error
            failedAt = System.currentTimeMillis()
            Diagnostics.event(
                context,
                "modelLoad",
                JSONObject().put("ok", false).put("error", error.javaClass.simpleName),
            )
            throw error
        }
    }

    /**
     * The vector of a sentence: the one of the test file query_vectors.json if it provides it
     * (interface tests on the emulator, without the text model), otherwise that of the model.
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
        // Measured around the model call only: the native memory before and after, and the time it took.
        val nativeBefore = android.os.Debug.getNativeHeapAllocatedSize()
        val started = System.currentTimeMillis()
        val vector = get(context).encode(text)
        Diagnostics.event(
            context,
            "encodeMemory",
            JSONObject()
                .put("beforeMb", nativeBefore / (1024 * 1024))
                .put("afterMb", android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024))
                .put("ms", System.currentTimeMillis() - started),
        )
        return vector
    }

    /** Check line for the report: ids and first values, to compare with the computerateur. */
    @Synchronized
    fun control(context: Context): String {
        val model = get(context)
        val sentence = "une rose rouge dans un jardin"
        val ids = model.ids(sentence).take(8).joinToString(", ")
        val vector = model.encode(sentence).take(5).joinToString(" ; ") { "%.6f".format(it) }
        return "contrôle texte : ids [$ids] ; vecteur [$vector]"
    }
}
