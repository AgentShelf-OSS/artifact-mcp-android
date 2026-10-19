package ai.agentshelf.artifactmcp.car

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Cover URIs. A URI describes the whole cover (kind, color, text), so the provider renders it
 * without looking anything up, and a changed title gives a new URI (the car caches by URI).
 *
 *   content://<package>.covers/a/<RRGGBB>/<title>/<category>   artifact cover
 *   content://<package>.covers/o/<RRGGBB>/<name>               organization cover
 *   content://<package>.covers/m                               the mark alone (messages)
 *
 * Text segments are URL-safe Base64 without padding; an empty category is "-".
 */
object CoverUris {
    const val AUTHORITY_SUFFIX = ".covers"

    fun authority(packageName: String) = packageName + AUTHORITY_SUFFIX

    private fun encode(text: String): String =
        if (text.isEmpty()) "-" else Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun decode(segment: String): String? {
        if (segment == "-") return ""
        val bytes = runCatching { Base64.decode(segment, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }.getOrNull() ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun color(value: Int) = String.format("%06X", value and 0xFFFFFF)

    private fun clean(text: String) = text.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(CoverDesign.MAX_TEXT)

    fun uri(packageName: String, spec: CoverDesign.Spec): Uri {
        val builder = Uri.Builder().scheme("content").authority(authority(packageName))
        when (spec) {
            is CoverDesign.Spec.Artifact -> builder.appendPath("a").appendPath(color(spec.color))
                .appendPath(encode(clean(spec.title))).appendPath(encode(clean(spec.category)))
            is CoverDesign.Spec.Org -> builder.appendPath("o").appendPath(color(spec.color)).appendPath(encode(clean(spec.name)))
            CoverDesign.Spec.Mark -> builder.appendPath("m")
        }
        return builder.build()
    }

    /** The spec a URI describes, or null for anything malformed. */
    fun parse(uri: Uri): CoverDesign.Spec? {
        val segments = uri.pathSegments
        if (segments == listOf("m")) return CoverDesign.Spec.Mark
        if (segments.size < 3) return null
        val color = CoverDesign.argb(segments[1]) ?: return null
        return when (segments[0]) {
            "a" -> {
                if (segments.size != 4) return null
                val title = decode(segments[2])?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return null
                CoverDesign.Spec.Artifact(title, decode(segments[3])?.let(::clean) ?: return null, color)
            }
            "o" -> {
                if (segments.size != 3) return null
                CoverDesign.Spec.Org(decode(segments[2])?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return null, color)
            }
            else -> null
        }
    }
}

/**
 * Serves covers to Android Auto, Android Automotive and the system media UI, which load
 * browse artwork through their own ContentResolver.
 *
 * Exported without a permission on purpose: the provider is read-only, and a cover is a pure
 * function of its URI (it draws the text the URI carries and reads no app data), so a caller
 * learns nothing it did not already know. This avoids per-package URI grants, which are
 * fragile across the processes that load car artwork. Rendered PNGs are cached in
 * `cache/covers`, at most [MAX_FILES].
 */
class CoverProvider : ContentProvider() {
    private companion object {
        const val MAX_FILES = 400
    }

    private val renderer by lazy { CoverRenderer(checkNotNull(context)) }

    override fun onCreate(): Boolean = true

    private fun dir(): File = File(checkNotNull(context).cacheDir, "covers")

    private fun fileName(spec: CoverDesign.Spec): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(spec.key.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) } + ".png"
    }

    /** The cached PNG for [spec], rendered first if needed. */
    fun file(spec: CoverDesign.Spec): File {
        val dir = dir()
        val file = File(dir, fileName(spec))
        if (file.isFile) return file
        dir.mkdirs()
        val tmp = File.createTempFile("cover", ".part", dir)
        try {
            tmp.outputStream().use { out -> renderer.render(spec).compress(Bitmap.CompressFormat.PNG, 100, out) }
            if (!tmp.renameTo(file) && !file.isFile) throw FileNotFoundException("cover not written")
        } finally {
            tmp.delete()
        }
        prune(dir)
        return file
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".png") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("covers are read-only")
        if (uri.authority != CoverUris.authority(checkNotNull(context).packageName)) throw FileNotFoundException()
        val spec = CoverUris.parse(uri) ?: throw FileNotFoundException("not a cover")
        val file = runCatching { file(spec) }.getOrElse { throw FileNotFoundException("cover failed") }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = if (CoverUris.parse(uri) != null) "image/png" else null

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
