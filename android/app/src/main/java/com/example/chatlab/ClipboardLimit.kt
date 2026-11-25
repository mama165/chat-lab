package com.example.chatlab

/** What a copy does with its text: put it on the clipboard, or point to the journal file when it is too big. */
sealed interface ClipboardPlan {
    data class Copy(val text: String) : ClipboardPlan

    /** Too big for the clipboard: the journal stays in its file, and [fileName] is where to find it. */
    data class TooLarge(val fileName: String?) : ClipboardPlan
}

object ClipboardLimit {
    /**
     * Above this size the clipboard refuses the text: the system passes a copy in one transfer of about one
     * megabyte, and the refusal used to close the app.
     */
    const val MAX_BYTES = 500_000

    fun plan(text: String, fileName: String?): ClipboardPlan =
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) ClipboardPlan.TooLarge(fileName) else ClipboardPlan.Copy(text)
}
