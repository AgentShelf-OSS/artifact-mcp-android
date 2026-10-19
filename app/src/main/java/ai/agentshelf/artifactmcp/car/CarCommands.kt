package ai.agentshelf.artifactmcp.car

import ai.agentshelf.artifactmcp.bridge.Protocol.MediaAction
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton

/**
 * The car's extra now-playing buttons: "Back 15 s" and "Speed" (showing the current rate).
 * Standard previous, next and play/pause come from the player commands.
 *
 * With the Listen mirror the buttons send `media.command` `rewind` and `speed` (the page
 * cycles the speed and reports it in `media.session.rate`). With the native queue the app
 * seeks back 15 s and cycles [NATIVE_RATES] itself.
 */
@OptIn(UnstableApi::class)
object CarCommands {
    const val REWIND = "ai.agentshelf.artifactmcp.REWIND"
    const val SPEED = "ai.agentshelf.artifactmcp.SPEED"
    const val REWIND_MS = 15_000L
    val NATIVE_RATES = listOf(1f, 1.25f, 1.5f)

    data class Button(
        val command: String,
        val displayName: String,
        val icon: Int,
        /** Drawable entry name used instead of Media3's icon (rates Media3 has no icon for). */
        val customIcon: String?,
        val slots: List<Int>,
    )

    data class Labels(val rewind: String, val speedFormat: String)

    /** The `media.command` action of a custom session command, or null. */
    fun action(customAction: String): MediaAction? = when (customAction) {
        REWIND -> MediaAction.REWIND
        SPEED -> MediaAction.SPEED
        else -> null
    }

    /** "1" -> "1×", "1.25" -> "1.25×", "1.50" -> "1.5×"; null or junk -> "1×". */
    fun rateLabel(rate: String?): String {
        val value = rate?.toDoubleOrNull()?.takeIf { it > 0 && it < 100 } ?: 1.0
        val text = java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        return "$text×"
    }

    private fun speedIcon(rate: String?): Pair<Int, String?> = when (rate?.toDoubleOrNull() ?: 1.0) {
        0.5 -> CommandButton.ICON_PLAYBACK_SPEED_0_5 to null
        0.8 -> CommandButton.ICON_PLAYBACK_SPEED_0_8 to null
        1.0 -> CommandButton.ICON_PLAYBACK_SPEED_1_0 to null
        // No Media3 icon: a custom drawable, and no icon constant (cars prefer the constant).
        1.25 -> CommandButton.ICON_UNDEFINED to "ic_speed_1_25"
        1.2 -> CommandButton.ICON_PLAYBACK_SPEED_1_2 to null
        1.5 -> CommandButton.ICON_PLAYBACK_SPEED_1_5 to null
        1.8 -> CommandButton.ICON_PLAYBACK_SPEED_1_8 to null
        2.0 -> CommandButton.ICON_PLAYBACK_SPEED_2_0 to null
        else -> CommandButton.ICON_PLAYBACK_SPEED to null
    }

    /** Buttons for what the current source supports, in display order. */
    fun buttons(rewind: Boolean, speed: Boolean, rate: String?, labels: Labels): List<Button> = buildList {
        if (rewind) add(Button(REWIND, labels.rewind, CommandButton.ICON_SKIP_BACK_15, null, listOf(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)))
        if (speed) {
            val (icon, custom) = speedIcon(rate)
            add(Button(SPEED, labels.speedFormat.format(rateLabel(rate)), icon, custom, listOf(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)))
        }
    }

    /** Next native playback speed after [current]. */
    fun nextNativeRate(current: Float): Float {
        val index = NATIVE_RATES.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        return NATIVE_RATES[(index + 1).mod(NATIVE_RATES.size)]
    }

    /** [rate] as the shell writes it ("1", "1.25"). */
    fun rateString(rate: Float): String = java.math.BigDecimal(rate.toString()).stripTrailingZeros().toPlainString()

    /** Seek target for "Back 15 s". */
    fun rewindTarget(positionMs: Long): Long = (positionMs - REWIND_MS).coerceAtLeast(0L)
}
