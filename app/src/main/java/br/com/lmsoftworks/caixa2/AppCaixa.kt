package br.com.lmsoftworks.caixa2

import android.app.Application

/** O fuso horário do app tem que valer desde o primeiro instante (os alarmes acordam o app sem abrir a tela). */
class AppCaixa : Application() {
    override fun onCreate() {
        super.onCreate()
        Ajustes.iniciar(this)
        FusoHorario.aplicar()
    }
}
