package com.example.chatlab

import org.json.JSONObject

/**
 * Reconnaître les photos déjà analysées. Le carnet les retrouve par leur adresse ; une photo
 * ajoutée depuis le stockage interne n'a pas la même adresse que dans l'ancien sélecteur. Elle
 * est alors reconnue par son nom, sa taille et sa date de modification, sans être relue.
 */
object KnownFiles {
    data class Identity(val name: String, val size: Long, val modified: Long)

    /** L'identité d'une entrée du carnet, si elle en a une. */
    fun identityOf(item: JSONObject): Identity? {
        if (!item.has("name") || !item.has("modified")) return null
        return Identity(item.getString("name"), item.optLong("size", -1L), item.optLong("modified", -1L))
    }

    fun byIdentity(items: Collection<JSONObject>): Map<Identity, JSONObject> =
        items.mapNotNull { item -> identityOf(item)?.let { it to item } }.toMap()

    /** L'entrée du carnet qui correspond au fichier : d'abord par adresse, sinon par identité. */
    fun find(
        byId: Map<String, JSONObject>,
        byIdentity: Map<Identity, JSONObject>,
        id: String,
        name: String,
        size: Long,
        modified: Long,
    ): JSONObject? = byId[id] ?: byIdentity[Identity(name, size, modified)]
}
