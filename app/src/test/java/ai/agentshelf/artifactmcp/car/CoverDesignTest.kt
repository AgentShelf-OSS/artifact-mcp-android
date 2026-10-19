package ai.agentshelf.artifactmcp.car

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoverDesignTest {
    /** Fixed-width "font": every character is 0.5 em wide. */
    private val mono = TitleFit.Measure { text, size -> text.length * size * 0.5f }

    private fun fit(text: String, width: Float = 432f, height: Float = 288f) = TitleFit.fit(
        text, width, height,
        maxSize = 112f, minSize = 56f, step = 4f, maxLines = 4, lineHeight = 1.08f, measure = mono,
    )

    @Test fun `short titles are set at the largest size`() {
        val r = fit("Dune")
        assertThat(r.size).isEqualTo(112f)
        assertThat(r.lines).containsExactly("Dune")
        assertThat(r.truncated).isFalse()
    }

    @Test fun `titles shrink until they fit in at most four lines`() {
        val r = fit("Road trip planner for Luxor and Aswan")
        assertThat(r.size).isLessThan(112f)
        assertThat(r.size).isAtLeast(56f)
        assertThat(r.lines.size).isAtMost(4)
        assertThat(r.lines.joinToString(" ")).isEqualTo("Road trip planner for Luxor and Aswan")
        assertThat(r.lines.all { mono.width(it, r.size) <= 432f }).isTrue()
        assertThat(r.lines.size * r.size * 1.08f).isAtMost(288f)
        assertThat(r.truncated).isFalse()
    }

    @Test fun `titles that do not fit at the smallest size end in an ellipsis`() {
        val long = "The complete and annotated field guide to every geotrigger stop on the long road from Cairo to Abu Simbel"
        val r = fit(long)
        assertThat(r.size).isEqualTo(56f)
        assertThat(r.lines).hasSize(4)
        assertThat(r.truncated).isTrue()
        assertThat(r.lines.last()).endsWith(TitleFit.ELLIPSIS)
        assertThat(r.lines.all { mono.width(it, 56f) <= 432f }).isTrue()
    }

    @Test fun `a long single word shrinks before it is broken`() {
        // 8 characters: 0.5 em each needs size <= 108 to fit 432 px on one line.
        val r = fit("bookclub")
        assertThat(r.lines).containsExactly("bookclub")
        assertThat(r.size).isAtMost(108f)
    }

    @Test fun `words wider than the box are broken`() {
        val r = fit("Supercalifragilisticexpialidocious")
        assertThat(r.lines.all { mono.width(it, r.size) <= 432f }).isTrue()
        assertThat(r.lines.joinToString("")).isEqualTo("Supercalifragilisticexpialidocious")
    }

    @Test fun `single lines keep text that fits and ellipsize the rest`() {
        assertThat(TitleFit.singleLine("Family", 432f, 32f, mono)).isEqualTo("Family")
        val cut = TitleFit.singleLine("A very long category name that cannot fit in the band", 432f, 32f, mono)
        assertThat(cut).endsWith(TitleFit.ELLIPSIS)
        assertThat(mono.width(cut, 32f)).isAtMost(432f)
    }

    @Test fun `blank titles give no lines`() {
        assertThat(fit("   ").lines).isEmpty()
    }

    @Test fun `contrast matches WCAG reference values`() {
        assertThat(CoverDesign.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt())).isWithin(0.01).of(21.0)
        assertThat(CoverDesign.contrast(CoverDesign.GOLD, CoverDesign.NAVY)).isWithin(0.05).of(6.96)
        assertThat(CoverDesign.contrast(CoverDesign.PAPER, CoverDesign.NAVY)).isGreaterThan(14.0)
    }

    @Test fun `every palette hue carries band text at 4_5 to 1 and stands apart from navy`() {
        for (color in CoverDesign.PALETTE) {
            val text = CoverDesign.textOn(color)
            assertThat(CoverDesign.contrast(text, color)).isAtLeast(4.5)
            assertThat(CoverDesign.contrast(color, CoverDesign.NAVY)).isAtLeast(4.5)
        }
        assertThat(CoverDesign.PALETTE.toSet()).hasSize(8)
    }

    @Test fun `band text picks white on dark and navy on light colors`() {
        assertThat(CoverDesign.textOn(0xFF1F3A5F.toInt())).isEqualTo(CoverDesign.WHITE)
        assertThat(CoverDesign.textOn(0xFFE9C46A.toInt())).isEqualTo(CoverDesign.NAVY)
        assertThat(CoverDesign.textOn(CoverDesign.argb("#356B9F")!!)).isEqualTo(CoverDesign.WHITE)
    }

    @Test fun `the mark stays gold where it is visible`() {
        assertThat(CoverDesign.markOn(CoverDesign.NAVY)).isEqualTo(CoverDesign.GOLD)
        assertThat(CoverDesign.markOn(0xFFB39DDB.toInt())).isEqualTo(CoverDesign.NAVY)
    }

    @Test fun `org colors come from the server, else a stable palette hue`() {
        assertThat(CoverDesign.orgColor("homelab", "#356B9F")).isEqualTo(0xFF356B9F.toInt())
        val a = CoverDesign.orgColor("homelab", null)
        assertThat(CoverDesign.orgColor("homelab", null)).isEqualTo(a)
        assertThat(CoverDesign.PALETTE.toList()).contains(a)
        assertThat(CoverDesign.orgColor("homelab", "not a color")).isEqualTo(a)
        val spread = listOf("homelab", "bookclub", "agentshelf", "cairn", "trustedtech", "emulator", "family", "work")
            .map { CoverDesign.orgColor(it, null) }.toSet()
        assertThat(spread.size).isAtLeast(4)
    }

    @Test fun `argb parses six-digit hex only`() {
        assertThat(CoverDesign.argb("#142235")).isEqualTo(CoverDesign.NAVY)
        assertThat(CoverDesign.argb("142235")).isEqualTo(CoverDesign.NAVY)
        assertThat(CoverDesign.argb("#12")).isNull()
        assertThat(CoverDesign.argb("#GG2235")).isNull()
    }
}
