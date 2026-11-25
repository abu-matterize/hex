package ly.anoma.hex

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService

internal fun setQuickSettingsTileEnabled(context: Context, component: ComponentName, enabled: Boolean, promptLabel: Int? = null) {
    context.packageManager.setComponentEnabledSetting(
        component,
        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
        PackageManager.DONT_KILL_APP,
    )
    if (!enabled || promptLabel == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    try {
        context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
            component, context.getString(promptLabel), Icon.createWithResource(context, R.drawable.hex_mark), context.mainExecutor,
        ) {}
    } catch (_: RuntimeException) {
        // The tile is still available in Quick Settings' edit list.
    }
}

internal fun TileService.launchFromQuickSettings(intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    } else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
}
