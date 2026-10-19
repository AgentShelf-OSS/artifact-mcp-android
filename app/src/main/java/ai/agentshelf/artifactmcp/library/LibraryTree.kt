package ai.agentshelf.artifactmcp.library

import ai.agentshelf.artifactmcp.bridge.Protocol
import ai.agentshelf.artifactmcp.car.CoverDesign
import ai.agentshelf.artifactmcp.car.RecentItem
import java.text.Normalizer

/** How a car shows the children of a folder (Media3 content-style extras). */
enum class ChildStyle { LIST, GRID }

data class FolderStyle(val browsable: ChildStyle, val playable: ChildStyle)

/** Picture of a browse entry: a generated cover, or a drawable resource (tab icons). */
sealed interface Artwork {
    data class Cover(val spec: CoverDesign.Spec) : Artwork

    /** Entry name of a drawable resource, for example `ic_tab_continue`. */
    data class Icon(val drawable: String) : Artwork
}

/** Completion badge of a playable entry; [fraction] (0..1) only when it is known. */
data class Completion(val status: Status, val fraction: Double? = null) {
    enum class Status { NOT_PLAYED, PARTIALLY_PLAYED, FULLY_PLAYED }
}

/** A browse-tree entry, independent of Media3 so the tree can be unit-tested. */
data class BrowseNode(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val browsable: Boolean,
    val playable: Boolean,
    val artwork: Artwork? = null,
    /** Consecutive entries with the same group title are shown under one heading. */
    val groupTitle: String? = null,
    /** For folders: how the car shows this folder's children. */
    val style: FolderStyle? = null,
    val completion: Completion? = null,
)

/**
 * The car browse tree. The root children are the car's tabs:
 *
 *   Continue      recently played artifacts (grid), or one "pick something" message
 *   Library       one folder per organization (list) -> artifacts (grid, grouped by category)
 *   Road stories  queued stories and geotrigger stops, only while there are any (list)
 *
 * Sign-in, offline and empty states are single non-playable entries inside Library.
 */
object LibraryTree {
    const val CONTINUE = "continue"
    const val LIBRARY = "library"
    const val ROAD = "road"
    const val SIGN_IN = "signin"
    const val OFFLINE = "offline"
    const val STALE = "stale"
    const val EMPTY = "empty"
    const val CONTINUE_EMPTY = "continue-empty"

    /** Ids of the message entries (sign in, offline, empty states). */
    val MESSAGES = setOf(SIGN_IN, OFFLINE, STALE, EMPTY, CONTINUE_EMPTY)
    private const val ORG_PREFIX = "org:"
    private const val ARTIFACT_PREFIX = "art:"

    /** Titles longer than this are shortened for the car (driver distraction guidelines). */
    const val MAX_TITLE = 40
    const val MAX_SEARCH_RESULTS = 50

    const val ICON_CONTINUE = "ic_tab_continue"
    const val ICON_LIBRARY = "ic_tab_library"
    const val ICON_ROAD = "ic_tab_road"

    data class Strings(
        val continueTab: String,
        val libraryTab: String,
        val roadTab: String,
        val continueEmpty: String,
        val signedOut: String,
        val stale: String,
        val offline: String,
        val empty: String,
        val other: String,
    )

    fun orgId(org: String) = ORG_PREFIX + org
    fun artifactMediaId(artifactId: String) = ARTIFACT_PREFIX + artifactId

    fun parseOrgId(mediaId: String): String? = mediaId.takeIf { it.startsWith(ORG_PREFIX) }?.removePrefix(ORG_PREFIX)?.takeIf { it.isNotEmpty() }

    /** Artifact id of a library item media id, validated; null for anything else. */
    fun parseArtifactMediaId(mediaId: String): String? =
        mediaId.takeIf { it.startsWith(ARTIFACT_PREFIX) }?.removePrefix(ARTIFACT_PREFIX)?.takeIf { Protocol.isValidArtifactId(it) }

    /** [title] cut to [MAX_TITLE] characters at a word break, with an ellipsis. */
    fun shortTitle(title: String): String {
        val clean = title.trim().replace(Regex("\\s+"), " ")
        if (clean.length <= MAX_TITLE) return clean
        val hard = clean.substring(0, MAX_TITLE - 1)
        val space = hard.lastIndexOf(' ')
        val cut = if (space >= MAX_TITLE / 2) hard.substring(0, space) else hard
        return cut.trimEnd(' ', ',', ';', ':', '-', '.') + "…"
    }

    /** Picture of message entries: cars show a placeholder tile for entries without one. */
    val MESSAGE_ART = Artwork.Cover(CoverDesign.Spec.Mark)

    /**
     * A message (sign in, offline, empty): not playable, and an empty folder rather than an
     * inert entry, because the Android Automotive media center hides entries that are neither
     * browsable nor playable (seen on the AAOS 15 emulator).
     */
    private fun message(id: String, text: String): BrowseNode {
        // Two sentences become title and subtitle: car rows show one line of title.
        val split = text.indexOf(". ")
        val title = if (split > 0) text.substring(0, split + 1) else text
        val subtitle = if (split > 0) text.substring(split + 2).trim().ifEmpty { null } else null
        return BrowseNode(id, title, subtitle, browsable = true, playable = false, artwork = MESSAGE_ART)
    }

    // ---- root ----------------------------------------------------------------------------------

    /**
     * The tabs. [rootLimit] is the controller's limit on root children (Android Auto: 4). The
     * styles do not depend on content: a car keeps a tab's style once it has loaded the tab.
     */
    fun root(strings: Strings, roadStories: Boolean, rootLimit: Int = Int.MAX_VALUE): List<BrowseNode> = buildList {
        add(
            BrowseNode(
                CONTINUE, strings.continueTab, browsable = true, playable = false,
                artwork = Artwork.Icon(ICON_CONTINUE),
                // Messages are folders (list rows); recent artifacts are playable (grid).
                style = FolderStyle(ChildStyle.LIST, ChildStyle.GRID),
            ),
        )
        add(
            BrowseNode(
                LIBRARY, strings.libraryTab, browsable = true, playable = false,
                artwork = Artwork.Icon(ICON_LIBRARY), style = FolderStyle(ChildStyle.LIST, ChildStyle.LIST),
            ),
        )
        if (roadStories) {
            add(
                BrowseNode(
                    ROAD, strings.roadTab, browsable = true, playable = false,
                    artwork = Artwork.Icon(ICON_ROAD), style = FolderStyle(ChildStyle.LIST, ChildStyle.LIST),
                ),
            )
        }
    }.take(rootLimit.coerceAtLeast(1))

    // ---- Continue ------------------------------------------------------------------------------

    fun continueChildren(recents: List<RecentItem>, strings: Strings): List<BrowseNode> {
        if (recents.isEmpty()) return listOf(message(CONTINUE_EMPTY, strings.continueEmpty))
        return recents.map { r ->
            BrowseNode(
                id = artifactMediaId(r.artifactId),
                title = shortTitle(r.title),
                subtitle = r.category.ifBlank { null },
                browsable = false,
                playable = true,
                artwork = Artwork.Cover(CoverDesign.Spec.Artifact(r.title, r.category, CoverDesign.orgColor(r.org, r.color))),
                completion = Completion(
                    if (r.fraction != null && r.fraction >= 0.995) Completion.Status.FULLY_PLAYED else Completion.Status.PARTIALLY_PLAYED,
                    r.fraction,
                ),
            )
        }
    }

    // ---- Library -------------------------------------------------------------------------------

    fun orgFolder(org: LibraryOrg) = BrowseNode(
        id = orgId(org.org),
        title = shortTitle(org.org),
        browsable = true,
        playable = false,
        artwork = Artwork.Cover(CoverDesign.Spec.Org(org.org, CoverDesign.orgColor(org.org, org.color))),
        style = FolderStyle(ChildStyle.GRID, ChildStyle.GRID),
    )

    /** Children of the Library tab for each library state. */
    fun libraryChildren(view: LibraryView, strings: Strings): List<BrowseNode> = when (view) {
        is LibraryView.Ready -> {
            val folders = view.library.orgs.map(::orgFolder).ifEmpty { listOf(message(EMPTY, strings.empty)) }
            if (view.fromCache) listOf(message(STALE, strings.stale)) + folders else folders
        }
        LibraryView.SignedOut -> listOf(message(SIGN_IN, strings.signedOut))
        LibraryView.Unavailable -> listOf(message(OFFLINE, strings.offline))
    }

    fun artifactNode(artifact: LibraryArtifact, org: LibraryOrg, groupTitle: String? = null, subtitle: String? = null) = BrowseNode(
        id = artifactMediaId(artifact.id),
        title = shortTitle(artifact.title),
        subtitle = subtitle,
        browsable = false,
        playable = true,
        artwork = Artwork.Cover(CoverDesign.Spec.Artifact(artifact.title, artifact.category, CoverDesign.orgColor(org.org, org.color))),
        groupTitle = groupTitle,
    )

    /** Newest first; an empty timestamp sorts last. The sort is stable. */
    fun newestFirst(artifacts: List<LibraryArtifact>): List<LibraryArtifact> =
        artifacts.sortedWith(compareByDescending<LibraryArtifact> { it.updatedAt })

    /**
     * Playable items of one organization folder, grouped by category: groups in the order of
     * their newest artifact, "Other" (no category) last; newest first inside a group. The
     * group heading carries the category, so the items have no subtitle. Null when the folder
     * is unknown.
     */
    fun orgChildren(view: LibraryView, orgMediaId: String, strings: Strings): List<BrowseNode>? {
        val name = parseOrgId(orgMediaId) ?: return null
        val library = (view as? LibraryView.Ready)?.library ?: return emptyList()
        val org = library.org(name) ?: return null
        val sorted = newestFirst(org.artifacts.take(LibraryJson.MAX_PER_ORG))
        val groups = LinkedHashMap<String, MutableList<LibraryArtifact>>()
        sorted.forEach { groups.getOrPut(it.category.trim()) { mutableListOf() } += it }
        val ordered = groups.entries.sortedBy { if (it.key.isEmpty()) 1 else 0 }
        return ordered.flatMap { (category, items) ->
            val heading = category.ifEmpty { strings.other }
            items.map { artifactNode(it, org, groupTitle = heading) }
        }
    }

    // ---- search --------------------------------------------------------------------------------

    private val VOICE_PREFIX = Regex("^(?:please\\s+)?(?:play|listen to|read|start)\\s+")
    private val VOICE_SUFFIX = Regex("\\s+(?:on|in|from|with)\\s+(?:the\\s+)?artifact(?:\\s+mcp)?(?:\\s+app)?$")

    /** Lower case, without accents, punctuation as spaces. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private val APP_NAME = Regex("^(?:(?:play|open|start)\\s+)?artifact(?:\\s+mcp)?(?:\\s+app)?$")

    /**
     * The search words of a typed or spoken query ("Play Frankenstein on Artifact MCP"). Empty
     * when the query only names the app ("play Artifact MCP"): the caller resumes instead.
     */
    fun searchTerms(query: String): String {
        val q = normalize(query)
        if (APP_NAME.matches(q)) return ""
        return q.replace(VOICE_SUFFIX, "").replace(VOICE_PREFIX, "").trim()
    }

    /**
     * Artifacts whose title, category or organization contain every word of [query]
     * (case- and accent-insensitive). Title matches rank first; ties keep newest first.
     */
    fun search(library: Library, query: String): List<Pair<LibraryOrg, LibraryArtifact>> {
        val terms = searchTerms(query)
        if (terms.isEmpty()) return emptyList()
        val words = terms.split(' ')
        val scored = library.orgs.flatMap { org ->
            val orgText = normalize(org.org)
            newestFirst(org.artifacts).mapNotNull { a ->
                val title = normalize(a.title)
                val category = normalize(a.category)
                val haystack = "$title $category $orgText"
                if (!words.all { haystack.contains(it) }) return@mapNotNull null
                val score = when {
                    title == terms -> 5
                    title.startsWith(terms) -> 4
                    title.contains(terms) -> 3
                    words.all { title.contains(it) } -> 2
                    category.contains(terms) -> 1
                    else -> 0
                }
                Triple(score, a.updatedAt, org to a)
            }
        }
        return scored.sortedWith(compareByDescending<Triple<Int, String, Pair<LibraryOrg, LibraryArtifact>>> { it.first }.thenByDescending { it.second })
            .map { it.third }.take(MAX_SEARCH_RESULTS)
    }

    /**
     * Search results for the car: playable covers in rank order, with the category below. (No
     * group headings: ranked results would split one organization into several groups.)
     */
    fun searchResults(library: Library, query: String): List<BrowseNode> =
        search(library, query).map { (org, a) -> artifactNode(a, org, subtitle = a.category.ifBlank { null }) }
}
