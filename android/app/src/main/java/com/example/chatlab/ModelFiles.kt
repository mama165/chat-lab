package com.example.chatlab

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * The models are read directly from the models subfolder of the chatlab folder,
 * without a copy in the private space of the application.
 *
 * A model too big, cut into pieces "nom.partN", is joined back once
 * as "nom" in the same folder; the pieces can then be deleted.
 */
object ModelFiles {
    /** The models subfolder of the chatlab folder (the one passed, otherwise the saved one). */
    fun folder(context: Context, tree: Uri? = null): DocumentFile {
        val uri = tree
            ?: context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_FOLDER, null)?.let(Uri::parse)
            ?: error("dossier chatlab non choisi")
        val root = DocumentFile.fromTreeUri(context, uri) ?: error("dossier chatlab inaccessible")
        return chatlabDir(root).findFile("models")?.takeIf { it.isDirectory }
            ?: error("sous-dossier models absent du dossier chatlab")
    }

    /**
     * The ChatLab folder itself: the one chosen, or the ChatLab (or chatlab) folder inside it when that is
     * where the models are. The person may have chosen the folder above it.
     */
    fun chatlabDir(root: DocumentFile): DocumentFile {
        fun holdsModels(folder: DocumentFile?) = folder?.findFile("models")?.isDirectory == true
        if (holdsModels(root)) return root
        val inside = listOf("ChatLab", "chatlab")
            .map { name -> root.findFile(name)?.takeIf { it.isDirectory } }
            .firstOrNull { holdsModels(it) }
        return inside ?: root
    }

    fun exists(models: DocumentFile, name: String): Boolean =
        models.findFile(name)?.isFile == true || parts(models, name).isNotEmpty()

    /** The content of a small file (vectors, vocabulary, tokenizer). */
    fun bytes(context: Context, models: DocumentFile, name: String): ByteArray {
        val file = models.findFile(name)?.takeIf { it.isFile } ?: error("$name absent de chatlab/models")
        val input = context.contentResolver.openInputStream(file.uri) ?: error("lecture impossible : $name")
        return input.use { it.readBytes() }
    }

    fun text(context: Context, models: DocumentFile, name: String): String =
        String(bytes(context, models, name), Charsets.UTF_8)

    /**
     * Opens an ONNX session on the file of the folder, without copying it: the content is mapped into
     * memory from the descriptor opened by Android. Android refuses to reopen this file by
     * a path (/proc/self/fd/…, error 13), hence going through memory rather than through a name.
     */
    fun session(
        env: OrtEnvironment,
        context: Context,
        models: DocumentFile,
        name: String,
        options: OrtSession.SessionOptions = OrtSession.SessionOptions(),
    ): OrtSession {
        val file = whole(context, models, name)
        val descriptor = context.contentResolver.openFileDescriptor(file.uri, "r")
            ?: error("lecture impossible : $name")
        val buffer = descriptor.use { FileInputStream(it.fileDescriptor).use { input -> mapped(input.channel, name) } }
        return env.createSession(buffer, options)
    }

    /**
     * The content of the file in memory outside the Java heap: direct mapping of the file if
     * the system accepts it, otherwise reading into a direct buffer.
     */
    private fun mapped(channel: FileChannel, name: String): ByteBuffer {
        val size = channel.size()
        require(size in 1..Int.MAX_VALUE) { "taille inattendue pour $name : $size" }
        return try {
            channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
        } catch (e: Exception) {
            val buffer = ByteBuffer.allocateDirect(size.toInt())
            channel.position(0)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
            check(!buffer.hasRemaining()) { "lecture incomplète de $name" }
            buffer.flip()
            buffer
        }
    }

    /**
     * The whole file. If it exists only in pieces, they are joined into a file
     * temporary, which takes its final name only once complete: a cut in the middle
     * never leaves a truncated model under the right name.
     */
    @Synchronized
    fun whole(context: Context, models: DocumentFile, name: String): DocumentFile {
        val pieces = parts(models, name)
        val total = pieces.sumOf { it.length() }
        val complete = models.findFile(name)?.takeIf { it.isFile }
        if (complete != null && (pieces.isEmpty() || complete.length() == total)) return complete
        if (pieces.isEmpty()) error("$name absent de chatlab/models")
        complete?.delete()

        val temporaryName = "$name.tmp"
        models.findFile(temporaryName)?.delete()
        val temporary = models.createFile("application/octet-stream", temporaryName)
            ?: error("écriture impossible dans chatlab/models")
        try {
            val output = context.contentResolver.openOutputStream(temporary.uri, "w")
                ?: error("écriture impossible : $temporaryName")
            output.use { out ->
                for (piece in pieces) {
                    val input = context.contentResolver.openInputStream(piece.uri)
                        ?: error("lecture impossible : ${piece.name}")
                    input.use { it.copyTo(out) }
                }
            }
            check(temporary.length() == total) { "$name recollé incomplet" }
            check(temporary.renameTo(name)) { "renommage impossible : $temporaryName" }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
        return models.findFile(name) ?: error("$name introuvable après recollage")
    }

    private fun parts(models: DocumentFile, name: String): List<DocumentFile> {
        val files = models.listFiles().filter { it.isFile && it.name != null }.associateBy { it.name!! }
        return ModelParts.ordered(files.keys, name).map { files.getValue(it) }
    }

    /** Deletes the old copies of the models and of the test images in the private space. */
    fun removeOldCopies(context: Context) {
        File(context.filesDir, "models").deleteRecursively()
        File(context.filesDir, "test").deleteRecursively()
    }
}
