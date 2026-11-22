package com.example.chatlab

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Les modèles sont lus directement dans le sous-dossier models du dossier chatlab,
 * sans copie dans l'espace privé de l'application.
 *
 * Un modèle trop gros, découpé en morceaux « nom.partN », est recollé une seule fois
 * en « nom » dans ce même dossier ; les morceaux peuvent ensuite être supprimés.
 */
object ModelFiles {
    /** Le sous-dossier models du dossier chatlab (celui passé, sinon celui enregistré). */
    fun folder(context: Context, tree: Uri? = null): DocumentFile {
        val uri = tree
            ?: context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_FOLDER, null)?.let(Uri::parse)
            ?: error("dossier chatlab non choisi")
        val root = DocumentFile.fromTreeUri(context, uri) ?: error("dossier chatlab inaccessible")
        return root.findFile("models")?.takeIf { it.isDirectory }
            ?: error("sous-dossier models absent du dossier chatlab")
    }

    fun exists(models: DocumentFile, name: String): Boolean =
        models.findFile(name)?.isFile == true || parts(models, name).isNotEmpty()

    /** Le contenu d'un petit fichier (vecteurs, vocabulaire, tokenizer). */
    fun bytes(context: Context, models: DocumentFile, name: String): ByteArray {
        val file = models.findFile(name)?.takeIf { it.isFile } ?: error("$name absent de chatlab/models")
        val input = context.contentResolver.openInputStream(file.uri) ?: error("lecture impossible : $name")
        return input.use { it.readBytes() }
    }

    fun text(context: Context, models: DocumentFile, name: String): String =
        String(bytes(context, models, name), Charsets.UTF_8)

    /**
     * Ouvre une session ONNX sur le fichier du dossier, sans le copier : le modèle est lu
     * par son descripteur de fichier, puis le descripteur est refermé.
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
        return descriptor.use { env.createSession("/proc/self/fd/${it.fd}", options) }
    }

    /**
     * Le fichier entier. S'il n'existe qu'en morceaux, ils sont recollés dans un fichier
     * temporaire, qui ne prend son nom définitif qu'une fois complet : une coupure au milieu
     * ne laisse jamais un modèle tronqué sous le bon nom.
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

    /** Supprime les anciennes copies des modèles et des images de test dans l'espace privé. */
    fun removeOldCopies(context: Context) {
        File(context.filesDir, "models").deleteRecursively()
        File(context.filesDir, "test").deleteRecursively()
    }
}
