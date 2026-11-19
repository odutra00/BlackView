package br.com.blackview.tv

import android.app.Application
import android.util.Log

class BlackViewApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("BlackViewApp", "Iniciando BlackView TV para Android TV...")
    }
}
