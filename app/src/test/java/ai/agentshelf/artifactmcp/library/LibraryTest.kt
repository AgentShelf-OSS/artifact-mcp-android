package ai.agentshelf.artifactmcp.library

import ai.agentshelf.artifactmcp.audio.HttpRequestSpec
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

    private val strings = LibraryTree.Strings("Library", "Open the app on your phone to sign in", "Offline", "Empty")

    @Test fun `parses organizations and artifacts`() {
        val library = LibraryJson.parse(body)!!
        assertThat(library.orgs.map { it.org }).containsExactly("homelab", "bookclub").inOrder()
        assertThat(library.orgs[0].artifacts).containsExactly(
            LibraryArtifact("trip", "Road trip", "Family"),
            LibraryArtifact("book", "book", ""),
        ).inOrder()
        assertThat(library.find("orwell")?.title).isEqualTo("1984  reader")
        assertThat(LibraryJson.parse("""{"error":"not_signed_in"}""")).isNull()
        assertThat(LibraryJson.parse("not json")).isNull()
    }

    @Test fun `caps each organization at 200`() {
        val items = (0 until 250).joinToString(",") { """{"id":"a$it","title":"T$it","category":"c"}""" }
        val library = LibraryJson.parse("""{"orgs":[{"org":"big","artifacts":[$items]}]}""")!!
        assertThat(library.orgs.single().artifacts).hasSize(200)
        assertThat(LibraryTree.orgChildren(LibraryView.Ready(library, false), "org:big")).hasSize(200)
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
        repository({ 200 to body }).refresh()
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

    // ---- browse tree ---------------------------------------------------------------------------

    private val queue = BrowseNode("queue", "Now queued", browsable = true, playable = false)

    @Test fun `root has one folder per organization, then native folders`() {
        val view = LibraryView.Ready(LibraryJson.parse(body)!!, false)
        val root = LibraryTree.root(view, listOf(queue), strings)
        assertThat(root.map { it.id }).containsExactly("org:homelab", "org:bookclub", "queue").inOrder()
        assertThat(root.map { it.title }).containsExactly("homelab", "bookclub", "Now queued").inOrder()
        assertThat(root.all { it.browsable && !it.playable }).isTrue()
    }

    @Test fun `organization folders hold playable artifacts with the category as subtitle`() {
        val view = LibraryView.Ready(LibraryJson.parse(body)!!, false)
        val items = LibraryTree.orgChildren(view, "org:homelab")!!
        assertThat(items).containsExactly(
            BrowseNode("art:trip", "Road trip", "Family", browsable = false, playable = true),
            BrowseNode("art:book", "book", null, browsable = false, playable = true),
        ).inOrder()
        assertThat(LibraryTree.orgChildren(view, "org:missing")).isNull()
        assertThat(LibraryTree.orgChildren(view, "queue")).isNull()
    }

    @Test fun `signed out shows a single non-playable sign-in item`() {
        val root = LibraryTree.root(LibraryView.SignedOut, emptyList(), strings)
        assertThat(root).containsExactly(BrowseNode("signin", "Open the app on your phone to sign in", browsable = false, playable = false))
    }

    @Test fun `root respects the child limit and browsable-only hints`() {
        val view = LibraryView.Ready(LibraryJson.parse(body)!!, false)
        val limited = LibraryTree.root(view, listOf(queue), strings, rootLimit = 2)
        assertThat(limited.map { it.id }).containsExactly("library", "queue").inOrder()
        assertThat(LibraryTree.libraryFolder(view, strings).map { it.id }).containsExactly("org:homelab", "org:bookclub")
        val browsableOnly = LibraryTree.root(LibraryView.SignedOut, emptyList(), strings, browsableOnlyAtRoot = true)
        assertThat(browsableOnly.map { it.id }).containsExactly("library")
        assertThat(LibraryTree.libraryFolder(LibraryView.SignedOut, strings).single().id).isEqualTo("signin")
    }

    @Test fun `artifact media ids are validated`() {
        assertThat(LibraryTree.parseArtifactMediaId("art:trip")).isEqualTo("trip")
        assertThat(LibraryTree.parseArtifactMediaId("art:../x")).isNull()
        assertThat(LibraryTree.parseArtifactMediaId("art:")).isNull()
        assertThat(LibraryTree.parseArtifactMediaId("q:1")).isNull()
        assertThat(LibraryTree.parseArtifactMediaId("art:${"a".repeat(65)}")).isNull()
    }
}
