package com.tapper.app

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile to start/stop tapping without touching the floating bar. */
class TapTile : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        TapService.instance?.toggleRunning()
        refresh()
    }

    private fun refresh() {
        val svc = TapService.instance
        qsTile?.apply {
            state = when {
                svc == null -> Tile.STATE_UNAVAILABLE
                svc.isRunning -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            label = if (svc?.isRunning == true) "Stop tapping" else "Start tapping"
            updateTile()
        }
    }
}
