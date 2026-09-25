package com.aiia.app.ui

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.aiia.app.R
import com.aiia.app.dm.Dependencies
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class SyncTile : TileService() {

    override fun onStartListening() {
        publish()
    }

    override fun onClick() {
        runBlocking {
            val on = Dependencies.settings.settings.first().notifyEnabled
            Dependencies.settings.setNotifyEnabled(!on)
        }
        publish()
    }

    private fun publish() {
        val tile = qsTile ?: return
        val on = runBlocking { Dependencies.settings.settings.first().notifyEnabled }
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.sync_tile_label) ?: "Уведомления синка"
        tile.subtitle = if (on) "Включены" else "Выключены"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_sync)
        tile.updateTile()
    }
}
