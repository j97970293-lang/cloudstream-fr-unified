package com.lagradost.frunified

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FrUnifiedPlugin : Plugin() {
    override fun load(context: Context) {
        // Une seule extension, un seul catalogue.
        runCatching { FrSettings.init(context) }
        registerMainAPI(FrUnifiedProvider())

        // ⚙️ dans la liste des extensions
        openSettings = { ctx ->
            runCatching { FrSettings.init(ctx) }
            SettingsDialog.show(ctx)
        }
    }
}
