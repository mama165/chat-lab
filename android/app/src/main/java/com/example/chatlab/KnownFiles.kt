package com.example.chatlab

import org.json.JSONObject

/**
 * Recognising the photos already analysed. The library finds them by their address; a photo
 * added from the internal storage does not have the same address as in the old picker. It
 * is then recognised by its name, size and modification date, without being read again.
 */
object KnownFiles {
    data class Identity(val name: String, val size: Long, val modified: Long)

    /** The identity of an entry of the library, if it has one. */
    fun identityOf(item: JSONObject): Identity? {
        if (!item.has("name") || !item.has("modified")) return null
        return Identity(item.getString("name"), item.optLong("size", -1L), item.optLong("modified", -1L))
    }

    fun byIdentity(items: Collection<JSONObject>): Map<Identity, JSONObject> =
        items.mapNotNull { item -> identityOf(item)?.let { it to item } }.toMap()

    /** The library entry that matches the file: first by address, otherwise by identity. */
    fun find(
        byId: Map<String, JSONObject>,
        byIdentity: Map<Identity, JSONObject>,
        id: String,
        name: String,
        size: Long,
        modified: Long,
    ): JSONObject? = byId[id] ?: byIdentity[Identity(name, size, modified)]
}
