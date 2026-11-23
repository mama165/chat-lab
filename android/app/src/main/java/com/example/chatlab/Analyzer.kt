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

/** Result for a test image. */
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
    /** Name of the source folder ("Camera"), written to the library for the folder filter. */
    val folder: String = "",
    /** MIME type detected from the bytes of the file. */
    val mime: String = "",
    /** Instant de prise de vue en millisecondes, 0 si inconnu. */
    val takenMs: Long = 0L,
)

/**
 * Reproduces, on the phone, the steps of describe_library.py that do not read
 * the text: SigLIP 2 vector, indoor/outdoor, number of faces (YuNet).
 * The text is read on the computer and added to the library later.
 *
 * Expected files, read directly from the chosen chatlab folder (without copy):
 *   models/vision_model.onnx (ou ses morceaux vision_model.onnx.partN)
 *   models/judge_vectors.json
 *   models/face_detection_yunet_2023mar.onnx
 *   test/     (the test images, optional)
 * The library produced is written to the chatlab folder (library.json).
 */
/**
 * Version of the analysis settings (faces, text reading). A photo is kept from the library
 * only if it was analysed with the same version; otherwise it is analysed again.
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
        // The models are read from chatlab/models: the old private copies are no longer used.
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
     * Analyses the images of the test subfolder of the chatlab folder. Each image is copied
     * for the time of its analysis into the cache, then the copy is deleted.
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
     * Analyses any image, already copied into the private space of the application.
     * [checkpoint] is called between the steps: a pause freezes the computation there, a stop leaves.
     */
    fun analyzeFile(file: File, checkpoint: () -> Unit = {}): ImageResult = analyze(file, checkpoint)

    /** Writes the library in the format expected by cmd/query (-library). */
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
        // Same rule as ImageJudge.indoor_or_none: no answer if uncertain.
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

    /** EXIF orientation applied, white background for transparency, like flatten() in Python. */
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

    /** Same preparation as ImageEncoder.encode: 224 × 224, pixels scaled to [-1, 1]. */
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
     * Same count as FaceCounter.faces(): number of faces found by YuNet,
     * and main subject if the largest face covers at least 4 % of the image.
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
                // Columns 2 and 3: width and height of the face.
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
