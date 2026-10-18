package ai.agentshelf.artifactmcp.ui

import ai.agentshelf.artifactmcp.ArtifactApp
import ai.agentshelf.artifactmcp.BuildConfig
import ai.agentshelf.artifactmcp.R
import ai.agentshelf.artifactmcp.config.OriginPolicy
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** First run and "Change server": asks for the https origin of an Artifact MCP deployment. */
class SetupActivity : AppCompatActivity() {
    private lateinit var input: TextInputEditText
    private lateinit var layout: TextInputLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ArtifactApp
        val pad = (20 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            setText(R.string.setup_intro)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        })
        layout = TextInputLayout(this).apply {
            hint = getString(R.string.setup_hint)
            setPadding(0, pad, 0, pad / 2)
        }
        input = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setText(app.originStore.origin ?: "https://")
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    submit()
                    true
                } else {
                    false
                }
            }
        }
        layout.addView(input)
        column.addView(layout)
        column.addView(Button(this).apply {
            setText(R.string.setup_continue)
            setOnClickListener { submit() }
        })
        column.addView(TextView(this).apply {
            setText(R.string.setup_note)
            setPadding(0, pad, 0, 0)
        })
        val scroll = ScrollView(this).apply { addView(column) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    private fun submit() {
        val app = application as ArtifactApp
        when (val parsed = OriginPolicy.normalize(input.text?.toString().orEmpty(), allowLoopbackHttp = BuildConfig.DEBUG)) {
            is OriginPolicy.Parsed.Invalid -> layout.error = getString(
                when (parsed.reason) {
                    "https_required" -> R.string.setup_error_https
                    "path" -> R.string.setup_error_path
                    "userinfo" -> R.string.setup_error_userinfo
                    else -> R.string.setup_error_malformed
                },
            )
            is OriginPolicy.Parsed.Valid -> {
                layout.error = null
                val previous = app.originStore.origin
                if (previous == null || previous == parsed.origin) {
                    app.originStore.origin = parsed.origin
                    openViewer()
                    return
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.setup_change_title)
                    .setMessage(getString(R.string.setup_change_message, previous, parsed.origin))
                    .setPositiveButton(R.string.setup_change_confirm) { _, _ ->
                        app.clearAllData(forgetOrigin = false) {
                            app.originStore.origin = parsed.origin
                            openViewer()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun openViewer() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
