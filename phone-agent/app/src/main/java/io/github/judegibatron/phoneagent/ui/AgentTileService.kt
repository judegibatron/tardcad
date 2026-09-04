package io.github.judegibatron.phoneagent.ui

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.judegibatron.phoneagent.PhoneAgentApp
import io.github.judegibatron.phoneagent.access.AgentAccessibilityService
import io.github.judegibatron.phoneagent.session.SessionController

/** Quick Settings tile: one tap starts a voice session (a no-root trigger). */
class AgentTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "Phone Agent"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        PhoneAgentApp.get(this).sessionController.start(SessionController.Source.TILE)
        if (Build.VERSION.SDK_INT >= 31) {
            AgentAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
    }
}
