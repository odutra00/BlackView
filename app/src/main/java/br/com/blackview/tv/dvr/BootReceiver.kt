package br.com.blackview.tv.dvr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import br.com.blackview.tv.data.DvrSettings

/**
 * Inicia o DVR quando a TV liga (se "Iniciar com a TV" estiver ativo) e após
 * atualização do app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = DvrSettings(context)
        Log.i("BlackViewBoot", "Recebido ${intent.action}; dvr=${settings.enabled} boot=${settings.startOnBoot}")
        if (!settings.enabled) return
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED && !settings.startOnBoot) return
        try {
            DvrService.sync(context)
        } catch (e: Exception) {
            Log.e("BlackViewBoot", "Falha ao iniciar DVR", e)
        }
    }
}
