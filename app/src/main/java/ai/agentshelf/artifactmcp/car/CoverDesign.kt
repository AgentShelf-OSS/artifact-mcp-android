package ai.agentshelf.artifactmcp.car

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Typographic covers for the car (Android Auto, Android Automotive): the parts that do not need
 * Android graphics, so they can be unit-tested. [CoverRenderer] draws them.
 *
 * Artifact cover: navy field, the title large and left-aligned in Atkinson Hyperlegible Bold,
 * a band along the bottom in the organization color with the category, and the gold "A" mark
 * in the top-left corner. Organization cover: the organization color as the field, the name
 * large, and the mark. No gradients and no shadows.
 */
object CoverDesign {
    const val SIZE = 512
    const val NAVY = 0xFF142235.toInt()
    const val GOLD = 0xFFD5A252.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    /** Title text on the navy field: a warm white (14:1 on navy). */
    const val PAPER = 0xFFF6F1E7.toInt()

    /**
     * Organization colors when the server has none: eight hues that each give navy text at
     * least 4.5:1 and stand apart from the navy field by at least 4.5:1.
     */
    val PALETTE = intArrayOf(
        0xFFE07A5F.toInt(), // coral
        0xFFE58F3A.toInt(), // orange
        0xFFA8A33A.toInt(), // olive
        0xFF6BAA45.toInt(), // green
        0xFF2A9D8F.toInt(), // teal
        0xFF4FA3D9.toInt(), // sky
        0xFFB39DDB.toInt(), // lavender
        0xFFD96C84.toInt(), // rose
    )

    /** Layout of a [SIZE] square cover, in pixels. */
    const val PADDING = 40f
    const val MARK_SIZE = 56f
    const val BAND_FRACTION = 0.14f
    const val TITLE_MAX_SIZE = 112f
    const val TITLE_MIN_SIZE = 56f
    const val TITLE_STEP = 4f
    const val TITLE_MAX_LINES = 4
    const val LINE_HEIGHT = 1.08f
    const val CATEGORY_SIZE = 32f
    const val ORG_MAX_SIZE = 120f

    val bandHeight: Float get() = SIZE * BAND_FRACTION
    val titleTop: Float get() = PADDING + MARK_SIZE + 28f
    val textWidth: Float get() = SIZE - 2 * PADDING

    /** Height for the title on an artifact cover: from below the mark to above the band. */
    val titleHeight: Float get() = SIZE - bandHeight - 32f - titleTop

    /** Height for the name on an organization cover (no band). */
    val orgHeight: Float get() = SIZE - PADDING - titleTop

    fun argb(hex: String): Int? {
        val value = hex.removePrefix("#")
        if (value.length != 6 || value.any { it.digitToIntOrNull(16) == null }) return null
        return (0xFF000000 or value.toLong(16)).toInt()
    }

    /** Stable FNV-1a hash (String.hashCode would also do, but this is explicit). */
    fun hash(text: String): Int {
        var h = 0x811C9DC5.toInt()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            h = h xor (byte.toInt() and 0xFF)
            h *= 0x01000193
        }
        return h
    }

    /** The organization's color: the server's `#RRGGBB`, else a palette hue hashed from the name. */
    fun orgColor(org: String, serverColor: String?): Int =
        serverColor?.let(::argb) ?: PALETTE[Math.floorMod(hash(org), PALETTE.size)]

    // ---- contrast (WCAG 2.x) -----------------------------------------------------------------

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(color: Int): Double =
        0.2126 * channel(color shr 16 and 0xFF) + 0.7152 * channel(color shr 8 and 0xFF) + 0.0722 * channel(color and 0xFF)

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** White or navy, whichever has more contrast on [background] (>= 4.5:1 for the palette). */
    fun textOn(background: Int): Int = if (contrast(WHITE, background) >= contrast(NAVY, background)) WHITE else NAVY

    /** The gold mark where it stands out (3:1 for graphics), else the text color. */
    fun markOn(background: Int): Int = if (contrast(GOLD, background) >= 3.0) GOLD else textOn(background)

    // ---- what a cover shows ------------------------------------------------------------------

    sealed interface Spec {
        /** Part of the cache key; changing the drawing code bumps [VERSION]. */
        val key: String

        data class Artifact(val title: String, val category: String, val color: Int) : Spec {
            override val key get() = "a|$VERSION|${Integer.toHexString(color)}|$title|$category"
        }

        data class Org(val name: String, val color: Int) : Spec {
            override val key get() = "o|$VERSION|${Integer.toHexString(color)}|$name"
        }

        /** The mark alone on navy: the picture of message entries (sign in, offline, empty). */
        data object Mark : Spec {
            override val key get() = "m|$VERSION"
        }
    }

    const val VERSION = 1
    const val MAX_TEXT = 120
}

/**
 * Fits text into a box: the largest size from [maxSize] down to [minSize] at which the words
 * wrap into at most [maxLines] lines that fit [maxHeight]. When nothing fits, the text is set
 * at [minSize] and the last line ends with an ellipsis. Words wider than the box are broken.
 */
object TitleFit {
    const val ELLIPSIS = "…"

    data class Result(val size: Float, val lines: List<String>, val truncated: Boolean)

    /** Width of [text] at [size]; Paint.measureText in the app, a fixed-width font in tests. */
    fun interface Measure {
        fun width(text: String, size: Float): Float
    }

    fun fit(
        text: String,
        maxWidth: Float,
        maxHeight: Float,
        maxSize: Float,
        minSize: Float,
        step: Float,
        maxLines: Int,
        lineHeight: Float,
        measure: Measure,
    ): Result {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return Result(maxSize, emptyList(), false)
        var size = maxSize
        // Above the minimum size, only whole words: a word broken across lines reads badly.
        while (size >= minSize) {
            val linesThatFit = linesFor(maxHeight, size, lineHeight, maxLines)
            val wholeWords = clean.split(' ').all { measure.width(it, size) <= maxWidth }
            if (wholeWords) {
                val lines = wrap(clean, maxWidth, size, measure)
                if (lines.size <= linesThatFit) return Result(size, lines, false)
            }
            size -= step
        }
        val linesThatFit = linesFor(maxHeight, minSize, lineHeight, maxLines).coerceAtLeast(1)
        val lines = wrap(clean, maxWidth, minSize, measure)
        if (lines.size <= linesThatFit) return Result(minSize, lines, false)
        val kept = lines.take(linesThatFit).toMutableList()
        val rest = lines.drop(linesThatFit - 1).joinToString(" ")
        kept[kept.size - 1] = ellipsize(rest, maxWidth, minSize, measure)
        return Result(minSize, kept, true)
    }

    private fun linesFor(maxHeight: Float, size: Float, lineHeight: Float, maxLines: Int): Int =
        min(maxLines, (maxHeight / (size * lineHeight)).toInt())

    /** Greedy word wrap; a word wider than [maxWidth] is broken between characters. */
    fun wrap(text: String, maxWidth: Float, size: Float, measure: Measure): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measure.width(candidate, size) <= maxWidth) {
                current = candidate
                continue
            }
            if (current.isNotEmpty()) lines += current
            current = ""
            var piece = word
            while (measure.width(piece, size) > maxWidth && piece.length > 1) {
                var cut = piece.length - 1
                while (cut > 1 && measure.width(piece.substring(0, cut), size) > maxWidth) cut--
                lines += piece.substring(0, cut)
                piece = piece.substring(cut)
            }
            current = piece
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    /** [text] cut to fit [maxWidth] with a trailing ellipsis, at a word break when one is near. */
    fun ellipsize(text: String, maxWidth: Float, size: Float, measure: Measure): String {
        var end = text.length
        while (end > 0 && measure.width(text.substring(0, end).trimEnd() + ELLIPSIS, size) > maxWidth) end--
        val hard = text.substring(0, end).trimEnd()
        val space = hard.lastIndexOf(' ')
        val soft = if (space >= hard.length * 2 / 3) hard.substring(0, space).trimEnd() else hard
        return soft.trimEnd(',', ';', ':', '-', ' ') + ELLIPSIS
    }

    /** One line: [text] as is when it fits, else ellipsized. */
    fun singleLine(text: String, maxWidth: Float, size: Float, measure: Measure): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        return if (measure.width(clean, size) <= maxWidth) clean else ellipsize(clean, maxWidth, size, measure)
    }
}
