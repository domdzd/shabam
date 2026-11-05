package app.nanotune.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.nanotune.MainActivity
import app.nanotune.R

/*
 * One-tap entry points that open NanoTune and start recording immediately: a Quick Settings tile,
 * a home-screen widget and a launcher shortcut. They only open the app; the microphone is used by
 * the visible Identify screen, as Android requires, never from the background.
 */

/** Quick Settings tile "Identify song". */
class IdentifyTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_identify)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = getString(R.string.app_name)
        tile.updateTile()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { openIdentify() } else openIdentify()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openIdentify() {
        val intent = MainActivity.identifyIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, REQUEST_TILE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val REQUEST_TILE = 31
    }
}

/** 1×1 home-screen widget with a microphone button. */
class IdentifyWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val open = PendingIntent.getActivity(
            context,
            REQUEST_WIDGET,
            MainActivity.identifyIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_identify)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    private companion object {
        const val REQUEST_WIDGET = 32
    }
}

/** Long-press launcher shortcut "Identify a song". */
object IdentifyShortcut {
    fun publish(context: Context) {
        runCatching {
            val shortcut = ShortcutInfoCompat.Builder(context, "identify")
                .setShortLabel(context.getString(R.string.shortcut_identify_short))
                .setLongLabel(context.getString(R.string.shortcut_identify_long))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_identify))
                .setIntent(MainActivity.identifyIntent(context))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }
}
