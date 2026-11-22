package com.example.chatlab

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import androidx.documentfile.provider.DocumentFile
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lecture du texte d'une image, comme describe_library.py (RapidOCR, PP-OCRv3) :
 * trois lectures de l'image (normale avec bordure, gris contrasté, inversé), chacune
 * avec détection des zones de texte (DB) puis reconnaissance (CTC). Les mots sont
 * dédoublonnés entre les lectures.
 *
 * Fichiers attendus dans le dossier models :
 *   ch_PP-OCRv3_det_infer.onnx, ch_PP-OCRv3_rec_infer.onnx, ppocr_keys.txt
 */
class TextReader(private val env: OrtEnvironment, context: Context, models: DocumentFile) : AutoCloseable {
    private val detector = ModelFiles.session(env, context, models, "ch_PP-OCRv3_det_infer.onnx")
    private val recognizer = ModelFiles.session(env, context, models, "ch_PP-OCRv3_rec_infer.onnx")
    // Index 0 is the CTC blank, the last index is the space.
    private val vocabulary: List<String> =
        listOf("blank") + ModelFiles.text(context, models, "ppocr_keys.txt").split("\n") + " "

    private var zones = 0
    private var kept = 0
    private var best = 0f
    private val trace = ArrayList<String>()

    /** Zones, texte et confiance de la première lecture, pour comparer avec l'ordinateur. */
    fun trace(): String = trace.joinToString("\n")

    /** Le texte lu dans l'image, mots séparés par des espaces, sans doublons. */
    fun read(bitmap: Bitmap): String {
        val source = Mat()
        val bgr = Mat()
        val reduced = Mat()
        val words = LinkedHashMap<String, String>()
        zones = 0
        kept = 0
        best = 0f
        trace.clear()
        try {
            Utils.bitmapToMat(bitmap, source)
            Imgproc.cvtColor(source, bgr, Imgproc.COLOR_RGBA2BGR)
            // Une image très grande ne tient pas en mémoire sur le téléphone : on la réduit d'abord.
            val shrink = min(1.0, MAX_SIDE.toDouble() / max(bgr.cols(), bgr.rows()))
            if (shrink < 1.0) {
                Imgproc.resize(bgr, reduced, Size(bgr.cols() * shrink, bgr.rows() * shrink))
            } else {
                bgr.copyTo(reduced)
            }
            for ((index, variant) in variants(reduced).withIndex()) {
                try {
                    // The contrast readings must be surer of a line than the normal one to add words.
                    val minimum = if (index == 0) MIN_CONFIDENCE else CONTRAST_CONFIDENCE
                    for (line in readLines(variant, record = index == 0, minimum = minimum)) {
                        // A capital I before an apostrophe is a misread l: "I'herbe".
                        val fixed = line.replace("I'", "l'").replace("I’", "l’")
                        for (word in fixed.split(Regex("\\s+"))) {
                            // A lone symbol or digit is noise on a photograph ("%", "0"), not a word.
                            if (word.isNotBlank() && (word.length > 1 || word[0].isLetter())) {
                                words.putIfAbsent(word.lowercase(), word)
                            }
                        }
                    }
                } finally {
                    variant.release()
                }
            }
        } finally {
            source.release()
            bgr.release()
            reduced.release()
        }
        return words.values.joinToString(" ")
    }

    /** Résumé du dernier passage, pour le diagnostic. */
    fun summary(): String = "zones trouvées $zones, lues $kept, meilleure confiance ${"%.2f".format(best)}"

    /** Les trois lectures : bordure de la couleur du coin, gris contrasté, gris contrasté inversé. */
    private fun variants(image: Mat): List<Mat> {
        val border = Mat()
        val gray = Mat()
        val stretched = Mat()
        val contrast = Mat()
        val inverted = Mat()
        val invertedGray = Mat()
        try {
            val corner = if (image.rows() > 2 && image.cols() > 2) image.get(2, 2) else doubleArrayOf(255.0, 255.0, 255.0)
            Core.copyMakeBorder(image, border, BORDER, BORDER, BORDER, BORDER, Core.BORDER_CONSTANT, Scalar(corner[0], corner[1], corner[2]))
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
            Core.normalize(gray, stretched, 0.0, 255.0, Core.NORM_MINMAX)
            Imgproc.cvtColor(stretched, contrast, Imgproc.COLOR_GRAY2BGR)
            Core.bitwise_not(stretched, invertedGray)
            Imgproc.cvtColor(invertedGray, inverted, Imgproc.COLOR_GRAY2BGR)
            return listOf(border.clone(), contrast.clone(), inverted.clone())
        } finally {
            border.release()
            gray.release()
            stretched.release()
            contrast.release()
            inverted.release()
            invertedGray.release()
        }
    }

    private fun readLines(image: Mat, record: Boolean, minimum: Float): List<String> {
        val boxes = detect(image)
        zones += boxes.size
        return boxes.mapNotNull { box ->
            val (text, confidence) = recognize(crop(image, box))
            best = max(best, confidence)
            if (record) {
                val corners = box.joinToString(" ") { "[${it.x.roundToInt()},${it.y.roundToInt()}]" }
                trace.add("$corners '$text' ${"%.3f".format(confidence)}")
            }
            if (text.isNotBlank() && confidence >= minimum) {
                kept++
                text
            } else null
        }
    }

    /** Détection des zones de texte sur une image BVR (couleurs dans l'ordre d'OpenCV). */
    private fun detect(image: Mat): List<Array<Point>> {
        val resized = Mat()
        val mask = Mat()
        val dilated = Mat()
        try {
            val w = image.cols()
            val h = image.rows()
            val ratio = if (min(w, h) < LIMIT_SIDE) LIMIT_SIDE.toDouble() / min(w, h) else 1.0
            val width = roundTo32(w * ratio)
            val height = roundTo32(h * ratio)
            Imgproc.resize(image, resized, Size(width.toDouble(), height.toDouble()))

            // Normalisation ImageNet, par canal, puis mise en CHW.
            val pixels = ByteArray(width * height * 3)
            resized.get(0, 0, pixels)
            val plane = width * height
            val data = FloatArray(3 * plane)
            for (i in 0 until plane) {
                for (c in 0 until 3) {
                    val value = (pixels[i * 3 + c].toInt() and 0xFF) / 255f
                    data[c * plane + i] = (value - MEAN[c]) / STD[c]
                }
            }
            val probabilities: Array<FloatArray> = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(data), longArrayOf(1, 3, height.toLong(), width.toLong())
            ).use { input ->
                detector.run(mapOf(detector.inputNames.first() to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    (result[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                }
            }

            mask.create(height, width, CvType.CV_8UC1)
            mask.put(0, 0, ByteArray(width * height) { i ->
                if (probabilities[i / width][i % width] > THRESHOLD) 255.toByte() else 0
            })
            Imgproc.dilate(mask, dilated, Mat.ones(2, 2, CvType.CV_8U))
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(dilated, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val scaleX = width.toDouble() / w
            val scaleY = height.toDouble() / h
            val boxes = ArrayList<Array<Point>>()
            for (contour in contours) {
                val points = MatOfPoint2f(*contour.toArray())
                if (points.rows() < 3) continue
                val rect = Imgproc.minAreaRect(points)
                // RapidOCR drops a zone whose short side is under 3 px, then again under 5 px once grown.
                if (min(rect.size.width, rect.size.height) < MIN_SIDE) continue
                if (polygonScore(rect, probabilities) < BOX_THRESHOLD) continue
                val grown = unclip(rect)
                if (min(grown.size.width, grown.size.height) < MIN_SIDE + 2) continue
                val corners = Array(4) { Point() }
                grown.points(corners)
                boxes.add(Array(4) { Point(corners[it].x / scaleX, corners[it].y / scaleY) })
            }
            return boxes.sortedWith(compareBy<Array<Point>> { box -> box.minOf { it.y } }.thenBy { box -> box.minOf { it.x } })
        } finally {
            resized.release()
            mask.release()
            dilated.release()
        }
    }

    /** Moyenne de la carte de probabilités à l'intérieur du polygone, comme box_score_fast de RapidOCR. */
    private fun polygonScore(rect: RotatedRect, probabilities: Array<FloatArray>): Double {
        val corners = Array(4) { Point() }
        rect.points(corners)
        val h = probabilities.size
        val w = probabilities[0].size
        val xmin = floor(corners.minOf { it.x }).toInt().coerceIn(0, w - 1)
        val xmax = ceil(corners.maxOf { it.x }).toInt().coerceIn(0, w - 1)
        val ymin = floor(corners.minOf { it.y }).toInt().coerceIn(0, h - 1)
        val ymax = ceil(corners.maxOf { it.y }).toInt().coerceIn(0, h - 1)
        val cols = xmax - xmin + 1
        val rows = ymax - ymin + 1
        val mask = Mat.zeros(rows, cols, CvType.CV_8UC1)
        try {
            val polygon = MatOfPoint(*Array(4) {
                Point((corners[it].x - xmin).toInt().toDouble(), (corners[it].y - ymin).toInt().toDouble())
            })
            Imgproc.fillPoly(mask, listOf(polygon), Scalar(1.0))
            val inside = ByteArray(rows * cols)
            mask.get(0, 0, inside)
            var sum = 0.0
            var count = 0
            for (y in 0 until rows) for (x in 0 until cols) {
                if (inside[y * cols + x].toInt() != 0) {
                    sum += probabilities[ymin + y][xmin + x]
                    count++
                }
            }
            return if (count == 0) 0.0 else sum / count
        } finally {
            mask.release()
        }
    }

    /** Élargit la zone, comme unclip_ratio dans RapidOCR. */
    private fun unclip(rect: RotatedRect): RotatedRect {
        val width = rect.size.width
        val height = rect.size.height
        val distance = width * height * UNCLIP_RATIO / (2 * (width + height))
        return RotatedRect(rect.center, Size(width + 2 * distance, height + 2 * distance), rect.angle)
    }

    /** Découpe la zone redressée, puis la tourne si elle est très haute. */
    private fun crop(image: Mat, box: Array<Point>): Mat {
        val (tl, tr, br, bl) = orderCorners(box)
        val width = max(sideLength(tl, tr), sideLength(bl, br)).roundToInt().coerceAtLeast(1)
        val height = max(sideLength(tl, bl), sideLength(tr, br)).roundToInt().coerceAtLeast(1)
        val source = MatOfPoint2f(tl, tr, br, bl)
        val target = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(width.toDouble(), 0.0),
            Point(width.toDouble(), height.toDouble()),
            Point(0.0, height.toDouble()),
        )
        val transform = Imgproc.getPerspectiveTransform(source, target)
        val warped = Mat()
        try {
            Imgproc.warpPerspective(image, warped, transform, Size(width.toDouble(), height.toDouble()))
        } finally {
            transform.release()
        }
        if (height.toDouble() / width >= 1.5) {
            val turned = Mat()
            Core.rotate(warped, turned, Core.ROTATE_90_COUNTERCLOCKWISE)
            warped.release()
            return turned
        }
        return warped
    }

    /** Reconnaissance CTC d'une zone : texte et confiance moyenne des caractères retenus. */
    private fun recognize(piece: Mat): Pair<String, Float> {
        val height = piece.rows()
        val ratio = piece.cols().toDouble() / height
        val width = max(REC_WIDTH, ceil(REC_HEIGHT * ratio).toInt())
        val newWidth = min(width, ceil(REC_HEIGHT * ratio).toInt()).coerceAtLeast(1)
        val resized = Mat()
        try {
            Imgproc.resize(piece, resized, Size(newWidth.toDouble(), REC_HEIGHT.toDouble()))
            val pixels = ByteArray(newWidth * REC_HEIGHT * 3)
            resized.get(0, 0, pixels)
            val plane = REC_HEIGHT * width
            val data = FloatArray(3 * plane)
            for (y in 0 until REC_HEIGHT) for (x in 0 until newWidth) {
                for (c in 0 until 3) {
                    val value = (pixels[(y * newWidth + x) * 3 + c].toInt() and 0xFF) / 255f
                    // Remplissage à zéro au-delà de la largeur utile, comme RapidOCR.
                    data[c * plane + y * width + x] = (value - 0.5f) / 0.5f
                }
            }
            return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, REC_HEIGHT.toLong(), width.toLong())).use { input ->
                recognizer.run(mapOf(recognizer.inputNames.first() to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    decode((result[0].value as Array<Array<FloatArray>>)[0])
                }
            }
        } finally {
            resized.release()
        }
    }

    private fun decode(steps: Array<FloatArray>): Pair<String, Float> {
        val text = StringBuilder()
        var previous = -1
        var confidence = 0.0
        var kept = 0
        for (step in steps) {
            var best = 0
            for (i in step.indices) if (step[i] > step[best]) best = i
            if (best != 0 && best != previous && best < vocabulary.size) {
                text.append(vocabulary[best])
                confidence += step[best]
                kept++
            }
            previous = best
        }
        return text.toString() to if (kept == 0) 0f else (confidence / kept).toFloat()
    }

    private fun orderCorners(box: Array<Point>): List<Point> {
        val tl = box.minBy { it.x + it.y }
        val br = box.maxBy { it.x + it.y }
        val tr = box.minBy { it.y - it.x }
        val bl = box.maxBy { it.y - it.x }
        return listOf(tl, tr, br, bl)
    }

    private fun sideLength(a: Point, b: Point): Double = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    private fun roundTo32(value: Double): Int = max(32, (value / 32).roundToInt() * 32)

    override fun close() {
        detector.close()
        recognizer.close()
    }

    private companion object {
        const val MAX_SIDE = 1280
        const val BORDER = 60
        const val LIMIT_SIDE = 736
        const val THRESHOLD = 0.3f
        const val BOX_THRESHOLD = 0.5
        const val MIN_SIDE = 3.0
        const val UNCLIP_RATIO = 1.6
        const val REC_HEIGHT = 48
        const val REC_WIDTH = 320
        const val MIN_CONFIDENCE = 0.8f
        const val CONTRAST_CONFIDENCE = 0.8f
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
