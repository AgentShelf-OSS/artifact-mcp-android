package ai.agentshelf.artifactmcp.car

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverRenderTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val teal = 0xFF2A9D8F.toInt()

    private fun Bitmap.at(x: Int, y: Int) = getPixel(x, y) or 0xFF000000.toInt()

    @Test fun `artifact cover is a navy field with the org band, gold mark and title`() {
        val bitmap = CoverRenderer(context).render(CoverDesign.Spec.Artifact("Frankenstein reader", "Books", teal))
        assertThat(bitmap.width).isEqualTo(512)
        assertThat(bitmap.height).isEqualTo(512)
        // Field corner, band corner (no gradients: flat colors at both ends of each area).
        assertThat(bitmap.at(500, 100)).isEqualTo(CoverDesign.NAVY)
        assertThat(bitmap.at(2, 509)).isEqualTo(teal)
        assertThat(bitmap.at(509, 445)).isEqualTo(teal)
        // The gold mark's left leg sits in the top-left corner.
        val mark = (0 until 120).flatMap { x -> (0 until 120).map { y -> bitmap.at(x, y) } }
        assertThat(mark).contains(CoverDesign.GOLD)
        // Title ink (warm white) inside the title area.
        val title = (40 until 470 step 2).flatMap { x -> (124 until 400 step 2).map { y -> bitmap.at(x, y) } }
        assertThat(title.count { CoverDesign.contrast(it, CoverDesign.NAVY) > 10 }).isGreaterThan(200)
        // Category ink in the band is navy (teal takes navy text).
        val band = (40 until 300).flatMap { x -> (446 until 508).map { y -> bitmap.at(x, y) } }
        assertThat(band).contains(CoverDesign.NAVY)
    }

    @Test fun `org cover is the org color with dark text`() {
        val lavender = 0xFFB39DDB.toInt()
        val bitmap = CoverRenderer(context).render(CoverDesign.Spec.Org("bookclub", lavender))
        assertThat(bitmap.at(500, 500)).isEqualTo(lavender)
        val ink = (40 until 470 step 2).flatMap { x -> (124 until 470 step 2).map { y -> bitmap.at(x, y) } }
        assertThat(ink).contains(CoverDesign.NAVY)
    }

    @Test fun `message tile is the gold mark centred on navy`() {
        val bitmap = CoverRenderer(context).render(CoverDesign.Spec.Mark)
        assertThat(bitmap.at(10, 10)).isEqualTo(CoverDesign.NAVY)
        val centre = (200 until 312).flatMap { x -> (150 until 362).map { y -> bitmap.at(x, y) } }
        assertThat(centre).contains(CoverDesign.GOLD)
    }

    @Test fun `cover uris round-trip and reject junk`() {
        val pkg = context.packageName
        val spec = CoverDesign.Spec.Artifact("Road trip: Luxor / Aswan ?#", "", teal)
        val uri = CoverUris.uri(pkg, spec)
        assertThat(uri.scheme).isEqualTo("content")
        assertThat(uri.authority).isEqualTo("$pkg.covers")
        assertThat(uri.query).isNull()
        assertThat(CoverUris.parse(uri)).isEqualTo(spec)
        val org = CoverDesign.Spec.Org("homelab", 0xFF356B9F.toInt())
        assertThat(CoverUris.parse(CoverUris.uri(pkg, org))).isEqualTo(org)
        assertThat(CoverUris.parse(CoverUris.uri(pkg, CoverDesign.Spec.Mark))).isEqualTo(CoverDesign.Spec.Mark)
        assertThat(CoverUris.parse(Uri.parse("content://$pkg.covers/a/142235"))).isNull()
        assertThat(CoverUris.parse(Uri.parse("content://$pkg.covers/a/nothex/eA/-"))).isNull()
        assertThat(CoverUris.parse(Uri.parse("content://$pkg.covers/x/142235/eA"))).isNull()
        assertThat(CoverUris.parse(Uri.parse("content://$pkg.covers/a/142235/-/-"))).isNull()
        // Text is capped and cleaned of control characters.
        val long = CoverUris.parse(CoverUris.uri(pkg, CoverDesign.Spec.Artifact("x".repeat(500) + "\u0007", "c", teal))) as CoverDesign.Spec.Artifact
        assertThat(long.title).hasLength(CoverDesign.MAX_TEXT)
    }

    @Test fun `provider serves cached PNGs read-only`() {
        val provider = Robolectric.setupContentProvider(CoverProvider::class.java, "${context.packageName}.covers")
        val uri = CoverUris.uri(context.packageName, CoverDesign.Spec.Artifact("Baby log", "Family", teal))
        val bitmap = provider.openFile(uri, "r").use { pfd ->
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
        }
        assertThat(bitmap.width).isEqualTo(512)
        assertThat(provider.getType(uri)).isEqualTo("image/png")
        val cached = context.cacheDir.resolve("covers").listFiles { f -> f.name.endsWith(".png") }.orEmpty()
        assertThat(cached).hasLength(1)
        provider.openFile(uri, "r").close()
        assertThat(context.cacheDir.resolve("covers").listFiles { f -> f.name.endsWith(".png") }.orEmpty()).hasLength(1)
        var threw = false
        try { provider.openFile(uri, "w") } catch (_: SecurityException) { threw = true }
        assertThat(threw).isTrue()
        threw = false
        try { provider.openFile(Uri.parse("content://${context.packageName}.covers/a/zz"), "r") } catch (_: FileNotFoundException) { threw = true }
        assertThat(threw).isTrue()
    }
}
