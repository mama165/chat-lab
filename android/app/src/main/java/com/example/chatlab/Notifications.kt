package com.example.chatlab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * The analysis notification: one only. It shows the run (with Pause or Reprendre, and Arrêter),
 * then it is updated in place to the end of the run, under the same id.
 */
object Notifications {
    const val PROGRESS_ID = 1
    // A new channel, silent and without vibration: a channel's sound settings cannot change once it exists.
    private const val CHANNEL = "analysis_quiet"
    private const val OLD_CHANNEL = "analysis"

    private const val BLUE = 0xFF1F3A68.toInt()
    private const val TRACK = 0xFFE6ECF6.toInt()
    private const val GREY = 0xFF9AA3AF.toInt()
    private const val INK = 0xFF1E2120.toInt()

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            // The channel of the earlier versions alerted with a vibration: it is removed.
            manager.deleteNotificationChannel(OLD_CHANNEL)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Analyse des photos", NotificationManager.IMPORTANCE_LOW).apply {
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
    }

    /** The notification during the analysis. [controllable] is false when there is nothing left to control. */
    fun progress(context: Context, progress: Progress, controllable: Boolean): Notification {
        val percent = (progress.fraction * 100).toInt()
        val now = System.currentTimeMillis()
        val folder = progress.folders.firstOrNull { !it.nothingNew && !it.finished }?.name
        val title = when {
            progress.total == 0 -> "Analyse en cours"
            progress.paused -> "Analyse en pause · $percent %"
            else -> "Analyse en cours · $percent %"
        }
        val text = when {
            progress.total == 0 -> progress.message.ifBlank { "Préparation…" }
            progress.paused -> "${progress.done} sur ${progress.total} photos · rien ne tourne"
            else -> "${progress.done} sur ${progress.total} photos · ${remainingText(progress.remaining(now)).lowercase()}"
        } + if (folder != null && !progress.paused) "\nDossier : $folder" else ""
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_photos)
            .setLargeIcon(ring(percent, progress.paused, progress.total == 0))
            .setContentTitle(title)
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(100, percent, progress.total == 0)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progress.running && controllable) {
            if (progress.paused) builder.addAction(R.drawable.ic_stat_play, "Reprendre", action(context, AnalysisService.ACTION_RESUME))
            else builder.addAction(R.drawable.ic_stat_pause, "Pause", action(context, AnalysisService.ACTION_PAUSE))
            builder.addAction(R.drawable.ic_stat_stop, "Arrêter", action(context, AnalysisService.ACTION_STOP))
        }
        return builder.build()
    }

    /** The end notification: no more control buttons, a tap opens the application. */
    fun done(context: Context, text: String, report: RunReport?): Notification {
        val stopped = report?.stopped == true
        val lines = when {
            report == null -> text
            report.unread > 0 || report.refusedFolders > 0 ->
                "${report.analyzed} photos analysées · ${report.unread} fichiers non lus\nOuvrir pour voir lesquels"
            else -> "${report.analyzed} photos analysées"
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_photos)
            .setLargeIcon(check())
            .setContentTitle(if (stopped) "Analyse arrêtée" else "Analyse terminée")
            .setContentText(lines.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setContentIntent(openApp(context))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
    }

    private fun action(context: Context, action: String): PendingIntent = PendingIntent.getService(
        context,
        action.hashCode(),
        Intent(context, AnalysisService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** A tap on the notification brings back the main screen of the application. */
    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The progress ring, drawn as the large icon. Grey when paused. */
    fun ring(percent: Int, paused: Boolean, indeterminate: Boolean): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = 14f
        val box = RectF(stroke, stroke, size - stroke, size - stroke)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        paint.color = TRACK
        canvas.drawArc(box, 0f, 360f, false, paint)
        paint.color = if (paused) GREY else BLUE
        canvas.drawArc(box, -90f, if (indeterminate) 90f else 360f * percent / 100f, false, paint)
        if (!indeterminate) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK
                textSize = 34f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("$percent %", size / 2f, size / 2f + 12f, text)
        }
        return bitmap
    }

    /** A tick in a light circle: analysis finished. */
    fun check(): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.apply {
            color = BLUE
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = android.graphics.Path().apply {
            moveTo(38f, 66f)
            lineTo(56f, 84f)
            lineTo(92f, 46f)
        }
        canvas.drawPath(path, paint)
        return bitmap
    }
}
