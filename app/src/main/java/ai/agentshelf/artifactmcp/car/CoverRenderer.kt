package ai.agentshelf.artifactmcp.car

import ai.agentshelf.artifactmcp.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.PathParser

/** Draws [CoverDesign.Spec]s into square bitmaps. Thread-safe: each call uses its own paints. */
class CoverRenderer(context: Context) {
    private val typeface: Typeface =
        runCatching { ResourcesCompat.getFont(context, R.font.atkinson_hyperlegible_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD

    private companion object {
        /** The gold "A" of ic_launcher_foreground.xml, in its 108-unit viewport. */
        const val MARK_PATH = "M50.35,30.05 L57.65,30.05 L75.59,78.05 L65.33,78.05 L60.8,64.55 L45.62,64.55 " +
            "L41.29,78.05 L32.41,78.05 Z M53.19,40.69 L57.75,55.58 L48.87,55.58 Z"
        const val MARK_LEFT = 32.41f
        const val MARK_TOP = 30.05f
        const val MARK_HEIGHT = 48f
    }

    private fun textPaint(color: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.color = color
        this.typeface = this@CoverRenderer.typeface
        textSize = size
    }

    private fun measure(paint: Paint) = TitleFit.Measure { text, size ->
        paint.textSize = size
        paint.measureText(text)
    }

    fun render(spec: CoverDesign.Spec, size: Int = CoverDesign.SIZE): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(size / CoverDesign.SIZE.toFloat(), size / CoverDesign.SIZE.toFloat())
        when (spec) {
            is CoverDesign.Spec.Artifact -> drawArtifact(canvas, spec)
            is CoverDesign.Spec.Org -> drawOrg(canvas, spec)
            CoverDesign.Spec.Mark -> {
                canvas.drawColor(CoverDesign.NAVY)
                val height = CoverDesign.SIZE * 0.44f
                val width = height * (75.59f - MARK_LEFT) / MARK_HEIGHT
                drawMark(canvas, CoverDesign.GOLD, (CoverDesign.SIZE - width) / 2, (CoverDesign.SIZE - height) / 2, height)
            }
        }
        return bitmap
    }

    private fun drawArtifact(canvas: Canvas, spec: CoverDesign.Spec.Artifact) {
        val s = CoverDesign.SIZE.toFloat()
        canvas.drawColor(CoverDesign.NAVY)
        drawMark(canvas, CoverDesign.GOLD)
        drawBlock(canvas, spec.title, CoverDesign.PAPER, CoverDesign.titleHeight, CoverDesign.TITLE_MAX_SIZE)
        val bandTop = s - CoverDesign.bandHeight
        canvas.drawRect(0f, bandTop, s, s, Paint().apply { color = spec.color })
        if (spec.category.isNotBlank()) {
            val paint = textPaint(CoverDesign.textOn(spec.color), CoverDesign.CATEGORY_SIZE)
            val line = TitleFit.singleLine(spec.category, CoverDesign.textWidth, CoverDesign.CATEGORY_SIZE, measure(paint))
            paint.textSize = CoverDesign.CATEGORY_SIZE
            val metrics = paint.fontMetrics
            // Vertically centred on the cap height of the band text.
            val baseline = bandTop + CoverDesign.bandHeight / 2 - (metrics.ascent + metrics.descent) / 2
            canvas.drawText(line, CoverDesign.PADDING, baseline, paint)
        }
    }

    private fun drawOrg(canvas: Canvas, spec: CoverDesign.Spec.Org) {
        canvas.drawColor(spec.color)
        drawMark(canvas, CoverDesign.markOn(spec.color))
        drawBlock(canvas, spec.name, CoverDesign.textOn(spec.color), CoverDesign.orgHeight, CoverDesign.ORG_MAX_SIZE)
    }

    private fun drawBlock(canvas: Canvas, text: String, color: Int, height: Float, maxSize: Float) {
        val paint = textPaint(color, maxSize)
        val fit = TitleFit.fit(
            text = text,
            maxWidth = CoverDesign.textWidth,
            maxHeight = height,
            maxSize = maxSize,
            minSize = CoverDesign.TITLE_MIN_SIZE,
            step = CoverDesign.TITLE_STEP,
            maxLines = CoverDesign.TITLE_MAX_LINES,
            lineHeight = CoverDesign.LINE_HEIGHT,
            measure = measure(paint),
        )
        paint.textSize = fit.size
        val lineStep = fit.size * CoverDesign.LINE_HEIGHT
        // The first baseline sits one cap height below the top of the text area.
        var baseline = CoverDesign.titleTop - paint.fontMetrics.ascent * 0.78f
        for (line in fit.lines) {
            canvas.drawText(line, CoverDesign.PADDING, baseline, paint)
            baseline += lineStep
        }
    }

    private fun drawMark(
        canvas: Canvas,
        color: Int,
        left: Float = CoverDesign.PADDING,
        top: Float = CoverDesign.PADDING,
        height: Float = CoverDesign.MARK_SIZE,
    ) {
        val path: Path = PathParser.createPathFromPathData(MARK_PATH)
        val scale = height / MARK_HEIGHT
        path.transform(
            Matrix().apply {
                setTranslate(-MARK_LEFT, -MARK_TOP)
                postScale(scale, scale)
                postTranslate(left, top)
            },
        )
        path.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }
}
