package ai.agentshelf.artifactmcp.library

import ai.agentshelf.artifactmcp.audio.HttpRequestSpec
import ai.agentshelf.artifactmcp.car.CoverDesign
import ai.agentshelf.artifactmcp.car.RecentItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class LibraryTest {
    @get:Rule val temp = TemporaryFolder()

    private val origin = "https://artifact.example.com"
    private val body = """{"orgs":[
        {"org":"homelab","artifacts":[
            {"id":"trip","title":"Road trip","category":"Family","updated_at":"2026-10-18T00:00:00Z"},
            {"id":"bad id","title":"Skipped","category":"","updated_at":"2026-10-17T00:00:00Z"},
            {"id":"book","title":"","category":null,"updated_at":"2026-10-16T00:00:00Z","extra":1}]},
        {"org":"bookclub","artifacts":[{"id":"orwell","title":"1984\u0007 reader","category":"Books","updated_at":"x"}]}]}"""

    private val strings = LibraryTree.Strings(
        continueTab = "Continue",
        libraryTab = "Library",
        roadTab = "Road stories",
        continueEmpty = "Pick something in Library to start listening",
        signedOut = "Sign in on your phone to see your library",
        stale = "Can't reach your library right now. Showing your last copy.",
        offline = "Can't reach your library right now. Check your connection.",
        empty = "No artifacts yet",
        other = "Other",
    )

    @Test fun `parses organizations and artifacts`() {
        val library = LibraryJson.parse(body)!!
        assertThat(library.orgs.map { it.org }).containsExactly("homelab", "bookclub").inOrder()
        assertThat(library.orgs[0].artifacts).containsExactly(
            LibraryArtifact("trip", "Road trip", "Family", "2026-10-18T00:00:00Z"),
            LibraryArtifact("book", "book", "", "2026-10-16T00:00:00Z"),
        ).inOrder()
        assertThat(library.orgs.map { it.color }).containsExactly(null, null).inOrder()
        assertThat(library.find("orwell")?.title).isEqualTo("1984  reader")
        assertThat(LibraryJson.parse("""{"error":"not_signed_in"}""")).isNull()
        assertThat(LibraryJson.parse("not json")).isNull()
    }

    @Test fun `caps each organization at 200`() {
        val items = (0 until 250).joinToString(",") { """{"id":"a$it","title":"T$it","category":"c"}""" }
        val library = LibraryJson.parse("""{"orgs":[{"org":"big","artifacts":[$items]}]}""")!!
        assertThat(library.orgs.single().artifacts).hasSize(200)
        assertThat(LibraryTree.orgChildren(LibraryView.Ready(library, false), "org:big", strings)).hasSize(200)
    }

    @Test fun `cache round-trips only for the same origin`() {
        val library = LibraryJson.parse(body)!!
        val raw = LibraryJson.encodeCache(origin, library)
        assertThat(LibraryJson.decodeCache(raw, origin)).isEqualTo(library)
        assertThat(LibraryJson.decodeCache(raw, "https://other.example.com")).isNull()
    }

    @Test fun `request is a same-origin GET with the session cookie`() {
        val spec = LibraryRequests.library(origin, "CF_Authorization=x", "UA")
        assertThat(spec).isEqualTo(
            HttpRequestSpec("$origin/api/library", "GET", mapOf("Accept" to "application/json", "Cookie" to "CF_Authorization=x", "User-Agent" to "UA"), null),
        )
        assertThat(LibraryRequests.library(origin, null, null).headers).containsExactly("Accept", "application/json")
    }

    private fun repository(fetcher: LibraryFetcher, timeoutMs: Long = 1_000) = LibraryRepository(
        cacheFile = temp.root.resolve("library.json"),
        origin = { origin },
        cookies = { "c=1" },
        userAgent = { null },
        fetcher = fetcher,
        timeoutMs = timeoutMs,
    )

    @Test fun `fresh fetch is cached and offline falls back to the cache`() = runBlocking {
        val seen = mutableListOf<HttpRequestSpec>()
        val online = repository({ spec -> seen += spec; 200 to body })
        val fresh = online.refresh() as LibraryView.Ready
        assertThat(fresh.fromCache).isFalse()
        assertThat(seen.single().headers["Cookie"]).isEqualTo("c=1")

        val offline = repository({ throw IOException("offline") })
        val cached = offline.refresh() as LibraryView.Ready
        assertThat(cached.fromCache).isTrue()
        assertThat(cached.library).isEqualTo(fresh.library)

        val failing = repository({ 503 to "busy" })
        assertThat((failing.refresh() as LibraryView.Ready).fromCache).isTrue()
    }

    @Test fun `slow server falls back to the cache after the timeout`() = runBlocking {
        // A generous timeout for the seeding fetch: when this test runs first in a cold JVM,
        // class loading alone can take longer than the default one second.
        repository({ 200 to body }, timeoutMs = 30_000).refresh()
        val slow = repository({ Thread.sleep(5_000); 200 to body }, timeoutMs = 100)
        val started = System.nanoTime()
        val view = slow.refresh()
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(3_000L)
        assertThat((view as LibraryView.Ready).fromCache).isTrue()
        delay(1)
    }

    @Test fun `no cache and no network is unavailable`() = runBlocking {
        assertThat(repository({ throw IOException("offline") }).refresh()).isEqualTo(LibraryView.Unavailable)
    }

    @Test fun `403 and login redirects are signed out`() = runBlocking {
        assertThat(repository({ 403 to """{"error":"not_signed_in"}""" }).refresh()).isEqualTo(LibraryView.SignedOut)
        assertThat(repository({ 302 to null }).refresh()).isEqualTo(LibraryView.SignedOut)
    }

    @Test fun `clear removes the cache`() = runBlocking {
        val repo = repository({ 200 to body })
        repo.refresh()
        assertThat(repo.cached()).isNotNull()
        repo.clear()
        assertThat(repo.cached()).isNull()
        assertThat(repo.last).isNull()
    }

    @Test fun `organization colors are normalized and cached`() {
        val raw = """{"orgs":[{"org":"a","color":"#356b9f","artifacts":[]},{"org":"b","color":"#abc","artifacts":[]},
            {"org":"c","color":"red","artifacts":[]},{"org":"d","color":null,"artifacts":[]},{"org":"e","color":7,"artifacts":[]}]}"""
        val library = LibraryJson.parse(raw)!!
        assertThat(library.orgs.map { it.color }).containsExactly("#356B9F", "#AABBCC", null, null, null).inOrder()
        assertThat(LibraryJson.decodeCache(LibraryJson.encodeCache(origin, library), origin)).isEqualTo(library)
    }

    @Test fun `version 1 caches are ignored`() {
        val v1 = """{"cache":1,"origin":"$origin","orgs":[{"org":"a","artifacts":[]}]}"""
        assertThat(LibraryJson.decodeCache(v1, origin)).isNull()
    }

    // ---- browse tree ---------------------------------------------------------------------------

    private val sample = Library(
        listOf(
            LibraryOrg(
                "homelab",
                listOf(
                    LibraryArtifact("trip", "Road trip planner", "Family", "2026-10-10T00:00:00Z"),
                    LibraryArtifact("baby", "Baby log", "Family", "2026-10-18T00:00:00Z"),
                    LibraryArtifact("dash", "Rack dashboard", "", "2026-10-17T00:00:00Z"),
                    LibraryArtifact("net", "Network map", "Infra", "2026-10-12T00:00:00Z"),
                ),
                color = "#356B9F",
            ),
            LibraryOrg("bookclub", listOf(LibraryArtifact("frank", "Frankenstein reader", "Books", "2026-10-01T00:00:00Z"))),
        ),
    )

    @Test fun `root tabs are Continue and Library, plus Road stories while there are stories`() {
        val root = LibraryTree.root(strings, roadStories = false)
        assertThat(root.map { it.id }).containsExactly("continue", "library").inOrder()
        assertThat(root.map { it.title }).containsExactly("Continue", "Library").inOrder()
        assertThat(root.all { it.browsable && !it.playable }).isTrue()
        assertThat(root.map { (it.artwork as Artwork.Icon).drawable }).containsExactly("ic_tab_continue", "ic_tab_library").inOrder()
        // Continue: covers in a grid; its message (a folder) is a list row.
        assertThat(root[0].style).isEqualTo(FolderStyle(ChildStyle.LIST, ChildStyle.GRID))
        assertThat(root[1].style).isEqualTo(FolderStyle(ChildStyle.LIST, ChildStyle.LIST))

        val withRoad = LibraryTree.root(strings, roadStories = true)
        assertThat(withRoad.map { it.id }).containsExactly("continue", "library", "road").inOrder()
        assertThat((withRoad[2].artwork as Artwork.Icon).drawable).isEqualTo("ic_tab_road")
        assertThat(LibraryTree.root(strings, true, rootLimit = 2).map { it.id }).containsExactly("continue", "library").inOrder()
        assertThat(LibraryTree.root(strings, true, rootLimit = 0).map { it.id }).containsExactly("continue")
    }

    @Test fun `library tab lists organization folders with org-colored covers`() {
        val items = LibraryTree.libraryChildren(LibraryView.Ready(sample, false), strings)
        assertThat(items.map { it.id }).containsExactly("org:homelab", "org:bookclub").inOrder()
        assertThat(items.all { it.browsable && !it.playable && it.subtitle == null }).isTrue()
        assertThat(items[0].style).isEqualTo(FolderStyle(ChildStyle.GRID, ChildStyle.GRID))
        assertThat(items[0].artwork).isEqualTo(Artwork.Cover(CoverDesign.Spec.Org("homelab", 0xFF356B9F.toInt())))
        val fallback = (items[1].artwork as Artwork.Cover).spec as CoverDesign.Spec.Org
        assertThat(fallback.color).isEqualTo(CoverDesign.orgColor("bookclub", null))
        assertThat(CoverDesign.PALETTE.toList()).contains(fallback.color)
    }

    @Test fun `offline with a cache adds a first non-playable notice`() {
        val items = LibraryTree.libraryChildren(LibraryView.Ready(sample, fromCache = true), strings)
        assertThat(items.map { it.id }).containsExactly("stale", "org:homelab", "org:bookclub").inOrder()
        assertThat(items[0]).isEqualTo(BrowseNode("stale", "Can't reach your library right now.", "Showing your last copy.", browsable = true, playable = false, artwork = LibraryTree.MESSAGE_ART))
    }

    @Test fun `offline without a cache, signed out and empty are single messages`() {
        assertThat(LibraryTree.libraryChildren(LibraryView.Unavailable, strings))
            .containsExactly(BrowseNode("offline", "Can't reach your library right now.", "Check your connection.", browsable = true, playable = false, artwork = LibraryTree.MESSAGE_ART))
        assertThat(LibraryTree.libraryChildren(LibraryView.SignedOut, strings))
            .containsExactly(BrowseNode("signin", "Sign in on your phone to see your library", browsable = true, playable = false, artwork = LibraryTree.MESSAGE_ART))
        assertThat(LibraryTree.libraryChildren(LibraryView.Ready(Library(emptyList()), false), strings).single().id).isEqualTo("empty")
    }

    @Test fun `organization grid groups by category, newest first, Other last`() {
        val items = LibraryTree.orgChildren(LibraryView.Ready(sample, false), "org:homelab", strings)!!
        assertThat(items.map { it.id }).containsExactly("art:baby", "art:trip", "art:net", "art:dash").inOrder()
        assertThat(items.map { it.groupTitle }).containsExactly("Family", "Family", "Infra", "Other").inOrder()
        assertThat(items.all { it.playable && !it.browsable && it.subtitle == null }).isTrue()
        assertThat(items[0].artwork).isEqualTo(Artwork.Cover(CoverDesign.Spec.Artifact("Baby log", "Family", 0xFF356B9F.toInt())))
        assertThat(LibraryTree.orgChildren(LibraryView.Ready(sample, false), "org:missing", strings)).isNull()
        assertThat(LibraryTree.orgChildren(LibraryView.Ready(sample, false), "road", strings)).isNull()
        assertThat(LibraryTree.orgChildren(LibraryView.SignedOut, "org:homelab", strings)).isEmpty()
    }

    @Test fun `continue lists recents as partly played covers, or one message`() {
        assertThat(LibraryTree.continueChildren(emptyList(), strings))
            .containsExactly(BrowseNode("continue-empty", "Pick something in Library to start listening", browsable = true, playable = false, artwork = LibraryTree.MESSAGE_ART))
        val recents = listOf(
            RecentItem(origin, "frank", "Frankenstein reader", "bookclub", "Books", null, at = 2),
            RecentItem(origin, "trip", "Road trip planner", "homelab", "", "#356B9F", at = 1, fraction = 0.4),
        )
        val items = LibraryTree.continueChildren(recents, strings)
        assertThat(items.map { it.id }).containsExactly("art:frank", "art:trip").inOrder()
        assertThat(items.map { it.subtitle }).containsExactly("Books", null).inOrder()
        assertThat(items[0].completion).isEqualTo(Completion(Completion.Status.PARTIALLY_PLAYED, null))
        assertThat(items[1].completion).isEqualTo(Completion(Completion.Status.PARTIALLY_PLAYED, 0.4))
        assertThat(items.none { it.subtitle?.contains("\u00B7") == true }).isTrue()
    }

    @Test fun `offline marks continue and library rows and adds a notice`() {
        val recents = listOf(RecentItem(origin, "frank", "Frankenstein reader", "bookclub", "Books", null, at = 2))
        val items = LibraryTree.continueChildren(recents, strings, offline = true)
        assertThat(items.map { it.id }).containsExactly("no-connection", "art:frank").inOrder()
        assertThat(items[0].playable).isFalse()
        assertThat(items[0].id).isIn(LibraryTree.MESSAGES)
        assertThat(items[1].subtitle).isEqualTo(strings.needsConnection)
        assertThat(LibraryTree.continueChildren(emptyList(), strings, offline = true).map { it.id }).containsExactly("continue-empty")
        val org = LibraryTree.orgChildren(LibraryView.Ready(sample, true), "org:homelab", strings, offline = true)!!
        assertThat(org.map { it.subtitle }.toSet()).containsExactly(strings.needsConnection)
        assertThat(LibraryTree.orgChildren(LibraryView.Ready(sample, false), "org:homelab", strings)!!.map { it.subtitle }.toSet()).containsExactly(null)
    }

    @Test fun `the recent root lists only the newest playable item`() {
        assertThat(LibraryTree.recentChildren(emptyList(), strings)).isEmpty()
        val recents = listOf(
            RecentItem(origin, "frank", "Frankenstein reader", "bookclub", "Books", null, at = 2, fraction = 0.25),
            RecentItem(origin, "trip", "Road trip planner", "homelab", "", null, at = 1),
        )
        val items = LibraryTree.recentChildren(recents, strings)
        assertThat(items.map { it.id }).containsExactly("art:frank")
        assertThat(items.single().playable).isTrue()
        assertThat(items.single().completion?.fraction).isEqualTo(0.25)
    }

    @Test fun `long titles are shortened for the car at a word break`() {
        assertThat(LibraryTree.shortTitle("Road trip planner")).isEqualTo("Road trip planner")
        val long = LibraryTree.shortTitle("The complete annotated guide to the Luxor to Aswan road trip")
        assertThat(long.length).isAtMost(LibraryTree.MAX_TITLE)
        assertThat(long).isEqualTo("The complete annotated guide to the\u2026")
        assertThat(LibraryTree.shortTitle("x".repeat(60))).isEqualTo("x".repeat(39) + "\u2026")
        // The cover keeps the full title.
        val node = LibraryTree.artifactNode(LibraryArtifact("a", "x".repeat(60), ""), LibraryOrg("o", emptyList()))
        assertThat(((node.artwork as Artwork.Cover).spec as CoverDesign.Spec.Artifact).title).hasLength(60)
    }

    @Test fun `search matches title, category and organization without case or accents`() {
        fun ids(q: String) = LibraryTree.search(sample, q).map { it.second.id }
        assertThat(ids("frankenstein")).containsExactly("frank")
        assertThat(ids("FRANKENSTÉIN")).containsExactly("frank")
        assertThat(ids("family")).containsExactly("baby", "trip").inOrder()
        assertThat(ids("bookclub")).containsExactly("frank")
        assertThat(ids("road trip")).containsExactly("trip")
        assertThat(ids("trip road")).containsExactly("trip")
        assertThat(ids("nothing like this")).isEmpty()
        assertThat(ids("   ")).isEmpty()
        // Title matches rank above category and organization matches.
        assertThat(ids("homelab").first()).isEqualTo("baby")
        val net = Library(listOf(LibraryOrg("o", listOf(LibraryArtifact("x", "Map", "Network"), LibraryArtifact("y", "Network map", "")))))
        assertThat(LibraryTree.search(net, "network").map { it.second.id }).containsExactly("y", "x").inOrder()
    }

    @Test fun `spoken queries drop the play and app words`() {
        assertThat(LibraryTree.searchTerms("Play Frankenstein on Artifact MCP")).isEqualTo("frankenstein")
        assertThat(LibraryTree.searchTerms("listen to the road trip planner in Artifact")).isEqualTo("the road trip planner")
        assertThat(LibraryTree.searchTerms("Artifact MCP")).isEmpty()
        assertThat(LibraryTree.searchTerms("play Artifact MCP")).isEmpty()
        assertThat(LibraryTree.search(sample, "Play Baby log on Artifact MCP").map { it.second.id }).containsExactly("baby")
        val results = LibraryTree.searchResults(sample, "family")
        assertThat(results.all { it.playable && it.subtitle == "Family" && it.groupTitle == null }).isTrue()
    }

    @Test fun `artifact media ids are validated`() {
        assertThat(LibraryTree.parseArtifactMediaId("art:trip")).isEqualTo("trip")
        assertThat(LibraryTree.parseArtifactMediaId("art:../x")).isNull()
        assertThat(LibraryTree.parseArtifactMediaId("art:")).isNull()
        assertThat(LibraryTree.parseArtifactMediaId("q:1")).isNull()
        assertThat(LibraryTree.parseArtifactMediaId("art:${"a".repeat(65)}")).isNull()
    }
}
