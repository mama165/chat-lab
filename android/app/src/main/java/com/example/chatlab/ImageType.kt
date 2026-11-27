package com.example.chatlab

/**
 * The MIME type of a photo, from the name at the end of its address: a viewer that claims "image/jpeg"
 * is chosen for a JPEG, where the generic any-image type leaves the choice to the file manager, which shows the folder.
 */
internal fun imageMimeOf(source: String): String {
    // The name is the end of the address, before any query; its slashes are encoded as %2F in content addresses.
    val name = source.substringBefore('?').substringAfterLast('/').lowercase()
    return when {
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".webp") -> "image/webp"
        name.endsWith(".heic") -> "image/heic"
        name.endsWith(".heif") -> "image/heif"
        name.endsWith(".gif") -> "image/gif"
        else -> "image/*"
    }
}
