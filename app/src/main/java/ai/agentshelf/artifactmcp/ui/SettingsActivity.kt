package ai.agentshelf.artifactmcp.ui

import ai.agentshelf.artifactmcp.ArtifactApp
import ai.agentshelf.artifactmcp.BuildConfig
import ai.agentshelf.artifactmcp.R
import ai.agentshelf.artifactmcp.state.Capability
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Server origin, per-artifact capability grants with revoke, active location use, sign out. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var app: ArtifactApp
    private lateinit var column: LinearLayout
    private val refresh: () -> Unit = { if (!isFinishing) render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as ArtifactApp
        val pad = dp(16)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(column) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        app.hub.addChangeListener(refresh)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        app.hub.removeChangeListener(refresh)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun capabilityName(capability: Capability) = getString(
        when (capability) {
            Capability.LOCATION -> R.string.capability_location
            Capability.NOTIFY -> R.string.capability_notify
        },
    )

    private fun render() {
        column.removeAllViews()
        column.addView(heading(getString(R.string.settings_server)))
        column.addView(body(app.originStore.origin ?: getString(R.string.settings_no_server)))
        column.addView(button(getString(R.string.settings_change_server)) {
            startActivity(Intent(this, SetupActivity::class.java))
        })

        column.addView(heading(getString(R.string.settings_location_now)))
        val watching = app.hub.activeWatches.map { it.artifactId } + app.hub.activeTriggers.keys
        if (watching.isEmpty()) {
            column.addView(body(getString(R.string.settings_location_idle)))
        } else {
            column.addView(body(watching.distinct().joinToString("\n") { app.hub.label(it) }))
            column.addView(button(getString(R.string.location_stop)) { app.hub.stopAllLocation() })
        }

        column.addView(heading(getString(R.string.settings_grants)))
        val grants = app.grants.all()
        if (grants.isEmpty()) column.addView(body(getString(R.string.settings_no_grants)))
        for (grant in grants) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val label = body("${grant.label}\n${grant.artifactId} · ${capabilityName(grant.capability)}").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            row.addView(button(getString(R.string.settings_revoke)) {
                app.hub.revoke(grant.artifactId, grant.capability)
                Toast.makeText(this, getString(R.string.settings_revoked, grant.label), Toast.LENGTH_SHORT).show()
                render()
            })
            column.addView(row)
        }

        column.addView(heading(getString(R.string.settings_data)))
        column.addView(body(getString(R.string.settings_clear_explain)))
        column.addView(button(getString(R.string.settings_clear)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_clear)
                .setMessage(R.string.settings_clear_confirm)
                .setPositiveButton(R.string.settings_clear) { _, _ ->
                    app.clearAllData(forgetOrigin = false) {
                        Toast.makeText(this, R.string.settings_cleared, Toast.LENGTH_SHORT).show()
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                        finish()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        })

        column.addView(heading(getString(R.string.settings_about)))
        column.addView(body(getString(R.string.settings_version, BuildConfig.VERSION_NAME)))
        column.addView(View(this).apply { minimumHeight = dp(24) })
    }
}
