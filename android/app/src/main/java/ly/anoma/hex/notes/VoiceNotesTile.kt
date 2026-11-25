package ly.anoma.hex.notes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ly.anoma.hex.R
import ly.anoma.hex.launchFromQuickSettings
import ly.anoma.hex.setQuickSettingsTileEnabled

internal object VoiceNotes {
    private const val RECORD = "ly.anoma.hex.notes.RECORD"
    private const val LEGACY_ALIAS = "ly.anoma.hex.notes.RecordVoiceNote"

    private fun tile(context: Context) = ComponentName(context, VoiceNotesTile::class.java)

    fun enabled(context: Context) = context.packageManager.getComponentEnabledSetting(tile(context)) ==
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun migrateShortcut(context: Context) {
        val legacy = ComponentName(context, LEGACY_ALIAS)
        val packages = context.packageManager
        if (packages.getComponentEnabledSetting(legacy) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return
        setQuickSettingsTileEnabled(context, tile(context), true)
        packages.setComponentEnabledSetting(legacy, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
    }

    fun setEnabled(context: Context, enabled: Boolean) =
        setQuickSettingsTileEnabled(context, tile(context), enabled, R.string.voice_notes_tile)

    fun recordIntent(context: Context) = Intent(context, VoiceNotesActivity::class.java).setAction(RECORD)

    fun startsRecording(intent: Intent) = intent.action == RECORD &&
        (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
}

class VoiceNotesTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (VoiceNotes.enabled(this)) launchFromQuickSettings(VoiceNotes.recordIntent(this))
    }
}
