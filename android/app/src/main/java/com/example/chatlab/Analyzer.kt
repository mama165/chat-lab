package com.example.chatlab

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import java.io.File
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Résultat pour une image de test. */
data class ImageResult(
    val file: File,
    val indoor: Boolean?,
    val faces: Int,
    val portrait: Boolean,
    val vector: FloatArray?,
    val millis: Long,
    val error: String? = null,
    val text: String = "",
    val textNote: String = "",
    val textTrace: String = "",
)

/**
 * Reproduit, sur le téléphone, les étapes de describe_library.py qui ne lisent
 * pas le texte : vecteur SigLIP 2, intérieur/extérieur, nombre de visages (YuNet).
 * Le texte est lu sur l'ordinateur et ajouté au carnet plus tard.
 *
 * Fichiers attendus, dans l'espace privé de l'application :
 *   files/models/vision_model.onnx
 *   files/models/judge_vectors.json
 *   files/models/face_detection_yunet_2023mar.onnx
 *   files/test/     (les images à analyser)
 * Le carnet produit est écrit dans files/library.json.
 */
class Analyzer(context: Context, tree: Uri) : AutoCloseable {
    private companion object {
        val PART = Regex("\\.part\\d+$")
    }

    private val env = OrtEnvironment.getEnvironment()
    private val root = context.filesDir
    private val models = File(root, "models")
    private val tests = File(root, "test")
    private val vision: OrtSession
    private val indoor: FloatArray
    private val outdoor: FloatArray
    private val flat: FloatArray
    private val detector: FaceDetectorYN
    private val reader: TextReader

    init {
        importFiles(context, tree)
        check(OpenCVLoader.initLocal()) { "OpenCV ne s'est pas chargé" }
        vision = env.createSession(File(models, "vision_model.onnx").path, OrtSession.SessionOptions())
        val judge = JSONObject(File(models, "judge_vectors.json").readText())
        indoor = judge.floats("indoor")
        outdoor = judge.floats("outdoor")
        flat = judge.floats("flat")
        reader = TextReader(env, models)
        detector = FaceDetectorYN.create(
            File(models, "face_detection_yunet_2023mar.onnx").path,
            "",
            Size(320.0, 320.0),
            0.6f,
            0.3f,
            5000,
        )
    }

    /**
     * Copie les fichiers depuis le dossier choisi par l'utilisateur (sous-dossiers
     * models et test) vers l'espace privé de l'application. Un fichier déjà copié
     * et de même taille n'est pas recopié.
     */
    private fun importFiles(context: Context, tree: Uri) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: error("dossier choisi inaccessible")
        val modelsSource = root.findFile("models") ?: error("sous-dossier models absent du dossier choisi")
        importFolder(context, modelsSource, models)
        root.findFile("test")?.let { importFolder(context, it, tests) }
    }

    private fun importFolder(context: Context, source: DocumentFile, target: File) {
        target.mkdirs()
        importParts(context, source, target)
        for (file in source.listFiles()) {
            val name = file.name ?: continue
            if (!file.isFile || PART.containsMatchIn(name)) continue
            val destination = File(target, name)
            if (destination.exists() && destination.length() == file.length()) continue
            val input = context.contentResolver.openInputStream(file.uri) ?: error("lecture impossible : $name")
            input.use { stream -> destination.outputStream().use { stream.copyTo(it) } }
        }
    }

    /**
     * Un fichier trop gros pour être envoyé est découpé en morceaux « nom.partN » :
     * ils sont recollés ici, dans l'ordre, en un seul fichier.
     */
    private fun importParts(context: Context, source: DocumentFile, target: File) {
        val parts = source.listFiles()
            .filter { it.isFile && PART.containsMatchIn(it.name ?: "") }
            .groupBy { (it.name ?: "").substringBeforeLast(".part") }
        for ((base, files) in parts) {
            val ordered = files.sortedBy { (it.name ?: "").substringAfterLast(".part").toInt() }
            val total = ordered.sumOf { it.length() }
            val destination = File(target, base)
            if (destination.exists() && destination.length() == total) continue
            destination.outputStream().use { output ->
                for (part in ordered) {
                    val input = context.contentResolver.openInputStream(part.uri) ?: error("lecture impossible : ${part.name}")
                    input.use { it.copyTo(output) }
                }
            }
        }
    }

    fun analyzeAll(): List<ImageResult> {
        val files = tests.listFiles { f ->
            f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")
        }?.sortedBy { it.name }.orEmpty()
        return files.map { file ->
            val started = System.nanoTime()
            val partial = try {
                analyze(file)
            } catch (e: Throwable) {
                ImageResult(file, null, 0, false, null, 0, e.message ?: e.javaClass.simpleName)
            }
            partial.copy(millis = (System.nanoTime() - started) / 1_000_000)
        }
    }

    /** Écrit le carnet au format attendu par cmd/query (-library). */
    fun writeLibrary(results: List<ImageResult>): File {
        val entries = JSONArray()
        val added = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        for (r in results) {
            if (r.error != null || r.vector == null) continue
            val entry = JSONObject()
                .put("id", r.file.name)
                .put("path", r.file.path)
                .put("name", r.file.name)
                .put("kind", "image")
                .put("size", r.file.length())
                .put("added_at", added.format(Date(r.file.lastModified())))
                .put("faces", r.faces)
                .put("portrait", r.portrait)
                .put("vectors", JSONArray().put(JSONArray(r.vector.toList())))
            r.indoor?.let { entry.put("indoor", it) }
            if (r.text.isNotBlank()) entry.put("text", r.text)
            entries.put(entry)
        }
        val out = File(root, "library.json")
        out.writeText(entries.toString(), Charsets.UTF_8)
        return out
    }

    private fun analyze(file: File): ImageResult {
        val bitmap = loadUpright(file)
        val vector = embed(bitmap)
        val inScore = dot(vector, indoor)
        val outScore = dot(vector, outdoor)
        val flatScore = dot(vector, flat)
        // Même règle que ImageJudge.indoor_or_none : sans réponse si incertain.
        val answer: Boolean? =
            if (flatScore >= max(inScore, outScore) || abs(inScore - outScore) < 0.01f) null
            else inScore > outScore
        val (faces, portrait) = countFaces(bitmap)
        // A failure while reading the text is reported, not allowed to stop the whole analysis.
        return try {
            val text = reader.read(bitmap)
            ImageResult(file, answer, faces, portrait, vector, 0, text = text, textNote = reader.summary(), textTrace = reader.trace())
        } catch (e: Throwable) {
            ImageResult(file, answer, faces, portrait, vector, 0, textNote = "lecture du texte impossible : ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    /** Orientation EXIF appliquée, fond blanc pour la transparence, comme flatten() en Python. */
    private fun loadUpright(file: File): Bitmap {
        val raw = BitmapFactory.decodeFile(file.path) ?: error("image illisible")
        val orientation = ExifInterface(file.path)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val turned = if (degrees == 0f) raw
        else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true)
        val out = Bitmap.createBitmap(turned.width, turned.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(turned, 0f, 0f, null)
        }
        return out
    }

    /** Même préparation que ImageEncoder.encode : 224 × 224, pixels ramenés à [-1, 1]. */
    private fun embed(bitmap: Bitmap): FloatArray {
        val size = 224
        val resized = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        resized.getPixels(pixels, 0, size, 0, 0, size, size)
        val plane = size * size
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            data[i] = ((p shr 16 and 0xFF) / 255f - 0.5f) / 0.5f
            data[plane + i] = ((p shr 8 and 0xFF) / 255f - 0.5f) / 0.5f
            data[2 * plane + i] = ((p and 0xFF) / 255f - 0.5f) / 0.5f
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
            vision.run(mapOf(vision.inputNames.first() to input)).use { result ->
                val output = (result.get("pooler_output").get().value as Array<FloatArray>)[0]
                return normalize(output)
            }
        }
    }

    /**
     * Même comptage que FaceCounter.faces() : nombre de visages trouvés par YuNet,
     * et sujet principal si le plus grand visage couvre au moins 4 % de l'image.
     */
    private fun countFaces(bitmap: Bitmap): Pair<Int, Boolean> {
        val rgba = Mat()
        val bgr = Mat()
        val faces = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            detector.setInputSize(Size(bgr.cols().toDouble(), bgr.rows().toDouble()))
            detector.detect(bgr, faces)
            val count = faces.rows()
            var largest = 0.0
            for (row in 0 until count) {
                // Colonnes 2 et 3 : largeur et hauteur du visage.
                val w = faces.get(row, 2)[0]
                val h = faces.get(row, 3)[0]
                largest = max(largest, w * h)
            }
            val area = bgr.cols().toDouble() * bgr.rows().toDouble()
            return count to (count > 0 && largest / area >= 0.04)
        } finally {
            rgba.release()
            bgr.release()
            faces.release()
        }
    }

    private fun normalize(v: FloatArray): FloatArray {
        val length = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return FloatArray(v.size) { v[it] / length }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private fun JSONObject.floats(key: String): FloatArray {
        val array = getJSONArray(key)
        return FloatArray(array.length()) { array.getDouble(it).toFloat() }
    }

    override fun close() {
        vision.close()
        reader.close()
    }
}
