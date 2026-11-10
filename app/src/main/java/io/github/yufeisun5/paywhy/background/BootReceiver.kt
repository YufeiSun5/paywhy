package io.github.yufeisun5.paywhy.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.yufeisun5.paywhy.PayWhyApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val app = context.applicationContext as PayWhyApp
            if (app.settings.value.residentMode) ResidentMode.apply(app)
        }
    }
}
