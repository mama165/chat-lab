package com.example.chatlab

import java.io.File

/**
 * La logique des cases de l'arbre des dossiers, sans affichage : ce qui est coché, ce qui est
 * partiellement coché, et ce qu'on ajoute réellement. Un dossier coché inclut ses sous-dossiers.
 * Les dossiers déjà ajoutés ([locked]) ne changent jamais.
 */
object FolderSelection {
    /** Le dossier et tous ses sous-dossiers, à plat. */
    fun withDescendants(node: FolderNode): List<FolderNode> = listOf(node) + node.children.flatMap(::withDescendants)

    /** Tous les dossiers de l'arbre, à plat. */
    fun all(roots: List<FolderNode>): List<FolderNode> = roots.flatMap(::withDescendants)

    /** Vrai si au moins un sous-dossier de [node] est coché, sans que [node] le soit. */
    fun isPartial(node: FolderNode, selected: Set<String>): Boolean =
        node.path !in selected && selectedUnder(node, selected)

    private fun selectedUnder(node: FolderNode, selected: Set<String>): Boolean =
        node.children.any { it.path in selected || selectedUnder(it, selected) }

    /**
     * Coche ou décoche [node] et ses sous-dossiers. Si le dossier est coché ou partiellement
     * coché, on le décoche ; sinon on le coche. Un dossier verrouillé n'est jamais coché.
     */
    fun toggle(node: FolderNode, selected: Set<String>, locked: Set<String>): Set<String> {
        val family = withDescendants(node).map { it.path }.toSet()
        return if (node.path in selected || selectedUnder(node, selected)) {
            selected - family
        } else {
            selected + family.filter { it !in locked }
        }
    }

    /** « Tout cocher » : tous les dossiers, sauf ceux déjà ajoutés. */
    fun selectAll(roots: List<FolderNode>, locked: Set<String>): Set<String> =
        all(roots).map { it.path }.filter { it !in locked }.toSet()

    /** « Tout décocher » : rien, sauf les dossiers déjà ajoutés qui restent tels quels. */
    fun clear(): Set<String> = emptySet()

    /**
     * Les dossiers à ajouter : les plus hauts parmi les cochés, puisque leurs sous-dossiers les
     * suivent. Un dossier dont le parent est coché n'est pas répété.
     */
    fun tops(selected: Set<String>): List<String> =
        selected.filter { path -> parentOf(path)?.let { it !in selected } ?: true }.sorted()

    /** Le chemin du dossier parent. */
    fun parentOf(path: String): String? = File(path).parent
}
