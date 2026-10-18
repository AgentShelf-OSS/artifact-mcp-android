package ai.agentshelf.artifactmcp.library

import ai.agentshelf.artifactmcp.bridge.Protocol

/** A browse-tree entry, independent of Media3 so the tree can be unit-tested. */
data class BrowseNode(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val browsable: Boolean,
    val playable: Boolean,
)

/**
 * The car library browse tree (ADR-0019): root -> one folder per organization -> artifacts.
 * Native folders (queue, geotrigger stops) follow when they have entries.
 */
object LibraryTree {
    const val LIBRARY = "library"
    const val SIGN_IN = "signin"
    const val OFFLINE = "offline"
    private const val ORG_PREFIX = "org:"
    private const val ARTIFACT_PREFIX = "art:"

    data class Strings(val library: String, val signIn: String, val offline: String, val empty: String)

    fun orgId(org: String) = ORG_PREFIX + org
    fun artifactMediaId(artifactId: String) = ARTIFACT_PREFIX + artifactId

    fun parseOrgId(mediaId: String): String? = mediaId.takeIf { it.startsWith(ORG_PREFIX) }?.removePrefix(ORG_PREFIX)?.takeIf { it.isNotEmpty() }

    /** Artifact id of a library item media id, validated; null for anything else. */
    fun parseArtifactMediaId(mediaId: String): String? =
        mediaId.takeIf { it.startsWith(ARTIFACT_PREFIX) }?.removePrefix(ARTIFACT_PREFIX)?.takeIf { Protocol.isValidArtifactId(it) }

    private fun folder(id: String, title: String) = BrowseNode(id, title, browsable = true, playable = false)
    private fun message(id: String, title: String) = BrowseNode(id, title, browsable = false, playable = false)

    fun orgFolders(library: Library): List<BrowseNode> = library.orgs.map { folder(orgId(it.org), it.org) }

    /**
     * Root children. [nativeFolders] are the queue and trigger folders that have entries.
     * [rootLimit] is the controller's root-children hint; when the library and native folders
     * do not fit, the organizations move into one "Library" folder.
     * [browsableOnlyAtRoot] is set when the controller shows only browsable root children; then
     * a sign-in or offline message is put inside the Library folder.
     */
    fun root(
        view: LibraryView,
        nativeFolders: List<BrowseNode>,
        strings: Strings,
        rootLimit: Int = Int.MAX_VALUE,
        browsableOnlyAtRoot: Boolean = false,
    ): List<BrowseNode> {
        val libraryPart = when (view) {
            is LibraryView.Ready -> orgFolders(view.library).ifEmpty { listOf(message(OFFLINE, strings.empty)) }
            LibraryView.SignedOut -> listOf(message(SIGN_IN, strings.signIn))
            LibraryView.Unavailable -> listOf(message(OFFLINE, strings.offline))
        }
        val needsWrap = libraryPart.size + nativeFolders.size > rootLimit ||
            (browsableOnlyAtRoot && libraryPart.any { !it.browsable })
        val head = if (needsWrap) listOf(folder(LIBRARY, strings.library)) else libraryPart
        return (head + nativeFolders).take(rootLimit.coerceAtLeast(1))
    }

    /** Children of the "Library" folder (used only when the root is collapsed). */
    fun libraryFolder(view: LibraryView, strings: Strings): List<BrowseNode> = when (view) {
        is LibraryView.Ready -> orgFolders(view.library).ifEmpty { listOf(message(OFFLINE, strings.empty)) }
        LibraryView.SignedOut -> listOf(message(SIGN_IN, strings.signIn))
        LibraryView.Unavailable -> listOf(message(OFFLINE, strings.offline))
    }

    fun artifactNode(artifact: LibraryArtifact) = BrowseNode(
        id = artifactMediaId(artifact.id),
        title = artifact.title,
        subtitle = artifact.category.ifBlank { null },
        browsable = false,
        playable = true,
    )

    /** Playable items of one organization folder, or null when the folder is unknown. */
    fun orgChildren(view: LibraryView, orgMediaId: String): List<BrowseNode>? {
        val name = parseOrgId(orgMediaId) ?: return null
        val library = (view as? LibraryView.Ready)?.library ?: return emptyList()
        val org = library.org(name) ?: return null
        return org.artifacts.take(LibraryJson.MAX_PER_ORG).map(::artifactNode)
    }
}
