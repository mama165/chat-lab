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
import org.opencv.core.MatOfByte
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
    val taken: String = "",
    val source: String = "",
    val modified: Long = 0L,
    val size: Long = 0L,
    /** Nom du dossier source (« Camera »), écrit dans le carnet pour le filtre par dossier. */
    val folder: String = "",
    /** Type MIME détecté d'après les octets du fichier. */
    val mime: String = "",
    /** Instant de prise de vue en millisecondes, 0 si inconnu. */
    val takenMs: Long = 0L,
)

/**
 * Reproduit, sur le téléphone, les étapes de describe_library.py qui ne lisent
 * pas le texte : vecteur SigLIP 2, intérieur/extérieur, nombre de visages (YuNet).
 * Le texte est lu sur l'ordinateur et ajouté au carnet plus tard.
 *
 * Fichiers attendus, lus directement dans le dossier chatlab choisi (sans copie) :
 *   models/vision_model.onnx (ou ses morceaux vision_model.onnx.partN)
 *   models/judge_vectors.json
 *   models/face_detection_yunet_2023mar.onnx
 *   test/     (les images de test, facultatif)
 * Le carnet produit est écrit dans le dossier chatlab (library.json).
 */
/**
 * Version des réglages d'analyse (visages, lecture de texte). Une photo n'est reprise du carnet
 * que si elle a été analysée avec la même version ; sinon elle est analysée de nouveau.
 */
const val ANALYSIS_VERSION = 2

class Analyzer(context: Context, private val tree: Uri) : AutoCloseable {
    private val appContext = context.applicationContext

    private val env = OrtEnvironment.getEnvironment()
    private val root = context.filesDir
    private val vision: OrtSession
    private val indoor: FloatArray
    private val outdoor: FloatArray
    private val flat: FloatArray
    private val detector: FaceDetectorYN
    private val reader: TextReader

    init {
        // Les modèles sont lus dans chatlab/models : les anciennes copies privées ne servent plus.
        ModelFiles.removeOldCopies(context)
        val models = ModelFiles.folder(context, tree)
        check(OpenCVLoader.initLocal()) { "OpenCV ne s'est pas chargé" }
        vision = ModelFiles.session(env, context, models, "vision_model.onnx")
        val judge = JSONObject(ModelFiles.text(context, models, "judge_vectors.json"))
        indoor = judge.floats("indoor")
        outdoor = judge.floats("outdoor")
        flat = judge.floats("flat")
        reader = TextReader(env, context, models)
        detector = FaceDetectorYN.create(
            "onnx",
            MatOfByte(*ModelFiles.bytes(context, models, "face_detection_yunet_2023mar.onnx")),
            MatOfByte(),
            Size(320.0, 320.0),
            0.75f,
            0.3f,
            5000,
        )
    }

    /**
     * Analyse les images du sous-dossier test du dossier chatlab. Chaque image est copiée
     * le temps de son analyse dans le cache, puis la copie est supprimée.
     */
    fun analyzeAll(): List<ImageResult> {
        val folder = DocumentFile.fromTreeUri(appContext, tree)?.findFile("test") ?: return emptyList()
        val images = folder.listFiles()
            .filter { it.isFile && (it.name ?: "").substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            .sortedBy { it.name }
        val work = File(appContext.cacheDir, "test").apply { mkdirs() }
        return images.map { image ->
            val temp = File(work, image.name ?: "image")
            val started = System.nanoTime()
            val partial = try {
                val input = appContext.contentResolver.openInputStream(image.uri) ?: error("lecture impossible")
                input.use { source -> temp.outputStream().use { source.copyTo(it) } }
                analyze(temp)
            } catch (e: Throwable) {
                ImageResult(temp, null, 0, false, null, 0, e.message ?: e.javaClass.simpleName)
            } finally {
                temp.delete()
            }
            partial.copy(
                millis = (System.nanoTime() - started) / 1_000_000,
                source = image.uri.toString(),
                size = image.length(),
                modified = image.lastModified(),
            )
        }
    }

    /**
     * Analyse une image quelconque, déjà copiée dans l'espace privé de l'application.
     * [checkpoint] est appelé entre les étapes : une pause y fige le calcul, un arrêt y sort.
     */
    fun analyzeFile(file: File, checkpoint: () -> Unit = {}): ImageResult = analyze(file, checkpoint)

    /** Écrit le carnet au format attendu par cmd/query (-library). */
    fun writeLibrary(results: List<ImageResult>, keep: List<JSONObject> = emptyList()): File =
        LibraryWriter.write(appContext, results, keep)

    private fun analyze(file: File, checkpoint: () -> Unit = {}): ImageResult {
        val bitmap = loadUpright(file)
        checkpoint()
        val vector = embed(bitmap)
        checkpoint()
        val inScore = dot(vector, indoor)
        val outScore = dot(vector, outdoor)
        val flatScore = dot(vector, flat)
        // Même règle que ImageJudge.indoor_or_none : sans réponse si incertain.
        val answer: Boolean? =
            if (flatScore >= max(inScore, outScore) || abs(inScore - outScore) < 0.01f) null
            else inScore > outScore
        val (faces, portrait) = countFaces(bitmap)
        checkpoint()
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
        val raw = ImageLoader.decode(file)
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
